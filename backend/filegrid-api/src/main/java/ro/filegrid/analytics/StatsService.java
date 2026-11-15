package ro.filegrid.analytics;

import static ro.filegrid.analytics.StatsResponses.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import ro.filegrid.analytics.StatsRepository.ScenarioActivity;
import ro.filegrid.analytics.StatsRepository.StorageMeasurement;
import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.policy.PolicyEngine;

@Service
class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);
    private static final String ANOMALY_RULE = "kWh > mediană + 3 × abatere standard eșantională, per contor";
    private static final Duration DAY = Duration.ofDays(1);
    private static final Duration MONTH = Duration.ofDays(30);
    private static final Duration MIN_WINDOW = Duration.ofMinutes(1);
    private static final Locale RO = Locale.forLanguageTag("ro-RO");

    private final ObjectProvider<StatsRepository> repositories;
    private final PolicyEngine policies;

    StatsService(ObjectProvider<StatsRepository> repositories, PolicyEngine policies) {
        this.repositories = repositories;
        this.policies = policies;
    }

    Anomalies anomalies(Instant now, Instant from, Instant to, int silentMinutes, int page, int size) {
        // Cele trei interogări sunt independente; rulate în paralel, durata e a celei mai lente, nu suma lor.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pageTask = executor.submit(() -> read(repository -> repository.anomalies(from, to, page, size)));
            var silentTask = executor.submit(() -> read(repository -> repository.silentMeters(to, silentMinutes)));
            var hoursTask = executor.submit(() -> read(repository -> repository.consumptionByHour(from, to)));
            var anomalies = join(pageTask);
            var silentMeters = join(silentTask);
            var hours = join(hoursTask);
            String interpretation = ("În interval au fost detectate %d anomalii de consum, iar la sfârșitul "
                    + "intervalului %d contoare depășeau pragul de inactivitate de %d minute.")
                    .formatted(anomalies.totalItems(), silentMeters.size(), silentMinutes);
            return new Anomalies(now, from, to, ANOMALY_RULE, anomalies, silentMeters, hours, interpretation);
        }
    }

    private static <T> T join(Future<T> task) {
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Statisticile din FileGridMeta sunt momentan indisponibile.",
                    Map.of("reason", "METADATA_DB_UNAVAILABLE"));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    OutageZones outageZones(Instant now, Instant from, Instant to) {
        var outages = read(repository -> repository.outageZones(from, to));
        long total = outages.ranking().stream().mapToLong(ZoneRank::outages).sum();
        String interpretation = "În intervalul selectat nu s-au înregistrat avarii.";
        if (total > 0) {
            var leaders = outages.ranking().stream().filter(zone -> zone.rank() == 1).toList();
            String zones = leaders.stream().map(ZoneRank::zone).collect(Collectors.joining(", "));
            interpretation = "Cele mai multe avarii s-au înregistrat în %s (%d pe zonă, din %d în total)."
                    .formatted(zones, leaders.getFirst().outages(), total);
        }
        return new OutageZones(now, from, to, total, outages.ranking(), outages.byHour(), interpretation);
    }

    /**
     * Fiecare scenariu se compară pe fereastra lui reală (primul–ultimul rând live din ultimele 24 h, minimum un
     * minut), ca throughput-ul să fie pe durata rulării. {@code requests} sunt operațiile servite (fără respingeri),
     * {@code rejected} sunt rândurile {@code rejected} scrise de bulkhead. Contractul cere numere în toate câmpurile,
     * deci o categorie fără măsurători într-unul din scenarii dă 503 {@code REQUEST_METRICS_UNAVAILABLE}.
     */
    PeakVsNormal peakVsNormal(Instant now, String normal, String peak) {
        Instant lookback = now.minus(DAY);
        var activity = read(repository -> repository.scenarioActivity(normal, peak, lookback, now));
        var normalWindow = window(normal, activity, lookback, now);
        var peakWindow = window(peak, activity, lookback, now);
        var rows = read(repository -> repository.peakVsNormal(normal, normalWindow.from(), normalWindow.to(),
                peak, peakWindow.from(), peakWindow.to()));
        return compare(now, normalWindow, peakWindow, rows, activity);
    }

    static ScenarioWindow window(String scenario, List<ScenarioActivity> activity, Instant from, Instant to) {
        var rows = activity.stream().filter(row -> row.scenario().equals(scenario)).toList();
        if (rows.isEmpty()) {
            return new ScenarioWindow(scenario, from, to);
        }
        Instant first = rows.stream().map(ScenarioActivity::firstTs).min(Instant::compareTo).orElseThrow();
        // Sfârșit exclusiv: ultimul rând plus o unitate datetime2(7), ca să fie inclus.
        Instant end = rows.stream().map(ScenarioActivity::lastTs).max(Instant::compareTo).orElseThrow().plusNanos(100);
        return new ScenarioWindow(scenario, first, end.isBefore(first.plus(MIN_WINDOW)) ? first.plus(MIN_WINDOW) : end);
    }

    static PeakVsNormal compare(Instant now, ScenarioWindow normal, ScenarioWindow peak, List<OperationStats> rows,
                                List<ScenarioActivity> activity) {
        var missing = new ArrayList<Map<String, Object>>();
        var categories = new ArrayList<CategoryComparison>();
        for (Category category : Category.values()) {
            var normalRow = row(rows, category, normal.scenario());
            var peakRow = row(rows, category, peak.scenario());
            var normalGaps = gaps(normalRow, false);
            var peakGaps = gaps(peakRow, true);
            if (!normalGaps.isEmpty()) {
                missing.add(Map.of("category", category.name(), "scenario", normal.scenario(), "fields", normalGaps));
            }
            if (!peakGaps.isEmpty()) {
                missing.add(Map.of("category", category.name(), "scenario", peak.scenario(), "fields", peakGaps));
            }
            if (normalGaps.isEmpty() && peakGaps.isEmpty()) {
                categories.add(new CategoryComparison(category,
                        stats(normalRow, rejected(activity, category, normal.scenario())),
                        stats(peakRow, rejected(activity, category, peak.scenario())),
                        round(peakRow.p95ChangePercent())));
            }
        }
        if (!missing.isEmpty()) {
            throw unavailable(("Comparația are nevoie de cereri măsurate în ambele scenarii (%s și %s) pentru fiecare "
                    + "categorie, în ultimele 24 de ore; rulează tests/load/run.sh storm.")
                    .formatted(normal.scenario(), peak.scenario()),
                    Map.of("reason", "REQUEST_METRICS_UNAVAILABLE", "missing", missing));
        }
        return new PeakVsNormal(now, normal, peak, categories, interpret(normal.scenario(), peak.scenario(), categories));
    }

    static String interpret(String normal, String peak, List<CategoryComparison> categories) {
        var text = new StringBuilder();
        var leader = categories.stream().filter(c -> c.normal().requests() > 0)
                .max(Comparator.comparingDouble(c -> (double) c.peak().requests() / c.normal().requests()))
                .filter(c -> c.peak().requests() > c.normal().requests());
        if (leader.isPresent()) {
            var c = leader.get();
            text.append(("În scenariul „%s” față de „%s”, %s a avut cea mai mare creștere a cererilor (de la %d la %d, "
                    + "de %s ori), iar latența p95 a trecut de la %s ms la %s ms (%s%%).")
                    .formatted(peak, normal, c.category(), c.normal().requests(), c.peak().requests(),
                            number((double) c.peak().requests() / c.normal().requests()), number(c.normal().latencyP95Ms()),
                            number(c.peak().latencyP95Ms()), signed(c.p95ChangePercent())));
            categories.stream().filter(other -> other != c)
                    .mapToDouble(other -> Math.abs(other.p95ChangePercent())).max()
                    .ifPresent(max -> text.append(" La celelalte categorii p95 a variat cu cel mult %s%%."
                            .formatted(number(max))));
        } else {
            text.append("În scenariul „%s” volumul de cereri nu a crescut față de „%s” în nicio categorie."
                    .formatted(peak, normal));
        }
        long peakRejected = categories.stream().mapToLong(c -> c.peak().rejected()).sum();
        long normalRejected = categories.stream().mapToLong(c -> c.normal().rejected()).sum();
        if (peakRejected == 0 && normalRejected == 0) {
            text.append(" Bulkhead-ul nu a respins nicio cerere.");
        } else {
            String byCategory = categories.stream().filter(c -> c.peak().rejected() > 0)
                    .map(c -> c.category() + ": " + c.peak().rejected()).collect(Collectors.joining(", "));
            text.append(" Bulkhead-ul a respins %d cereri în „%s”%s și %d în „%s”; respingerile nu intră în latențe."
                    .formatted(peakRejected, peak, byCategory.isEmpty() ? "" : " (" + byCategory + ")",
                            normalRejected, normal));
        }
        return text.toString();
    }

    private static OperationStats row(List<OperationStats> rows, Category category, String scenario) {
        return rows.stream().filter(r -> r.category() == category && r.scenario().equals(scenario))
                .findFirst().orElse(null);
    }

    private static List<String> gaps(OperationStats row, boolean peak) {
        if (row == null || row.operations() == 0) {
            var all = new ArrayList<>(List.of("latencyP50Ms", "latencyP95Ms", "latencyP99Ms", "errorRatePercent"));
            if (peak) {
                all.add("p95ChangePercent");
            }
            return all;
        }
        var gaps = new ArrayList<String>();
        if (row.latencyP50Ms() == null) gaps.add("latencyP50Ms");
        if (row.latencyP95Ms() == null) gaps.add("latencyP95Ms");
        if (row.latencyP99Ms() == null) gaps.add("latencyP99Ms");
        if (row.errorRatePercent() == null) gaps.add("errorRatePercent");
        if (peak && row.p95ChangePercent() == null) gaps.add("p95ChangePercent");
        return gaps;
    }

    private static long rejected(List<ScenarioActivity> activity, Category category, String scenario) {
        return activity.stream().filter(a -> a.category() == category && a.scenario().equals(scenario))
                .mapToLong(ScenarioActivity::rejected).sum();
    }

    private static ScenarioStats stats(OperationStats row, long rejected) {
        return new ScenarioStats(row.operations(), round(row.operationsPerMinute()), round(row.latencyP50Ms()),
                round(row.latencyP95Ms()), round(row.latencyP99Ms()), round(row.errorRatePercent().doubleValue()),
                rejected);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static String number(double value) {
        return String.format(RO, "%.1f", value);
    }

    private static String signed(double value) {
        return String.format(RO, "%+.1f", value);
    }

    StorageAdvisor storageAdvisor(Instant now) {
        var measurements = read(repository -> repository.storageScores(now.minus(MONTH), now));
        long benchmarkSamples = measurements.stream().mapToLong(StorageMeasurement::benchmarkSamples).sum();
        long liveSamples = measurements.stream().mapToLong(StorageMeasurement::liveSamples).sum();
        var matrix = measurements.stream().map(StorageMeasurement::score).toList();
        // O6 emits one row per category/storage. Live samples supplement each cell's benchmark evidence.
        if (matrix.size() != Category.values().length * StorageType.values().length
                || measurements.stream().anyMatch(cell -> cell.score().score() == null || cell.benchmarkSamples() < 1)) {
            throw unavailable("Recomandarea necesită măsurători de încărcare pentru fiecare categorie și storage, "
                    + "cu date de benchmark în fiecare celulă.", Map.of("reason", "STORAGE_MEASUREMENTS_INCOMPLETE"));
        }

        var currentPolicies = policies.policies();
        var recommendations = new ArrayList<StorageRecommendation>();
        for (Category category : Category.values()) {
            // O6 keeps tied scores at rank 1 and sorts exact ties by storage name.
            var best = measurements.stream()
                    .filter(row -> row.score().category() == category && Long.valueOf(1).equals(row.rank()))
                    .findFirst().orElseThrow().score();
            StorageType current = currentPolicies.get(category).primaryStorage();
            String rationale = "%s are cel mai bun scor (%s) pentru %s pe baza măsurătorilor de încărcare."
                    .formatted(best.storage(), best.score().stripTrailingZeros().toPlainString(), category);
            recommendations.add(new StorageRecommendation(category, best.storage(), current, best.storage() == current,
                    rationale));
        }
        long matches = recommendations.stream().filter(StorageRecommendation::matchesPolicy).count();
        String interpretation = "Pe baza măsurătorilor din ultimele 30 de zile, stocarea principală din politică "
                + "coincide cu recomandarea în %d din %d categorii.".formatted(matches, recommendations.size());
        return new StorageAdvisor(now, new SampleBasis(benchmarkSamples, liveSamples), AnalyticsSql.COSTS, matrix,
                recommendations, interpretation);
    }

    private <T> T read(Function<StatsRepository, T> query) {
        var repository = repositories.getIfAvailable();
        if (repository != null) {
            try {
                return query.apply(repository);
            } catch (DataAccessException | TransactionException e) {
                log.warn("Interogarea de statistici din FileGridMeta a eșuat.", e);
            }
        }
        throw unavailable("Statisticile din FileGridMeta sunt momentan indisponibile.",
                Map.of("reason", "METADATA_DB_UNAVAILABLE"));
    }

    private static ApiException unavailable(String message, Map<String, Object> details) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", message, 10, details);
    }
}
