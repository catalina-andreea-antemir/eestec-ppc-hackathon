package ro.filegrid.analytics;

import static ro.filegrid.analytics.StatsResponses.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

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

    private final ObjectProvider<StatsRepository> repositories;
    private final PolicyEngine policies;

    StatsService(ObjectProvider<StatsRepository> repositories, PolicyEngine policies) {
        this.repositories = repositories;
        this.policies = policies;
    }

    Anomalies anomalies(Instant now, Instant from, Instant to, int silentMinutes, int page, int size) {
        return read(repository -> {
            var anomalies = repository.anomalies(from, to, page, size);
            var silentMeters = repository.silentMeters(to, silentMinutes);
            var hours = repository.consumptionByHour(from, to);
            String interpretation = ("În interval au fost detectate %d anomalii de consum, iar la sfârșitul "
                    + "intervalului %d contoare depășeau pragul de inactivitate de %d minute.")
                    .formatted(anomalies.totalItems(), silentMeters.size(), silentMinutes);
            return new Anomalies(now, from, to, ANOMALY_RULE, anomalies, silentMeters, hours, interpretation);
        });
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

    void peakVsNormal(Instant now, String normal, String peak) {
        var comparison = read(repository -> repository.peakVsNormal(normal, peak, now.minus(DAY), now));
        var unavailable = new ArrayList<>(List.of("requests", "throughputPerMinute", "rejected"));
        if (comparison.stream().anyMatch(row -> row.operations() == 0)) {
            unavailable.addAll(List.of("latencyP50Ms", "latencyP95Ms", "latencyP99Ms", "errorRatePercent"));
        }
        if (comparison.stream().anyMatch(row -> row.scenario().equals(peak) && row.p95ChangePercent() == null)) {
            unavailable.add("p95ChangePercent");
        }
        throw unavailable("Comparația necesită numărul de cereri HTTP și de respingeri pe categorie și scenariu.",
                Map.of("reason", "REQUEST_METRICS_UNAVAILABLE", "missingFields", unavailable));
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
