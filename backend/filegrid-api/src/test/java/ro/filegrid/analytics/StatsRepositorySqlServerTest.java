package ro.filegrid.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import ro.filegrid.analytics.StatsResponses.MeterAnomaly;
import ro.filegrid.analytics.StatsResponses.SilentMeter;
import ro.filegrid.analytics.StatsResponses.ZoneHourOutages;
import ro.filegrid.analytics.StatsResponses.ZoneRank;
import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.policy.PolicyEngine;

/** Run with -Dfilegrid.sql.integration=true against the local migrated FileGridMeta database. */
@EnabledIfSystemProperty(named = "filegrid.sql.integration", matches = "true")
class StatsRepositorySqlServerTest {

    private static final Path ROOT = Path.of("../..");
    private static final Instant FROM = Instant.parse("2026-11-14T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-14T06:00:00Z");
    private static final Pattern TABLE = Pattern.compile("\\bdbo\\.(files|meter_readings|outage_records|operation_metrics)\\b");

    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private StatsRepository repository;

    @BeforeEach
    void connect() throws IOException {
        String url = System.getenv().getOrDefault("O7_TEST_META_DB_URL",
                "jdbc:sqlserver://127.0.0.1:1433;databaseName=FileGridMeta;encrypt=true;trustServerCertificate=true");
        source = new SingleConnectionDataSource(url, "filegrid_meta_app", password(), true);
        jdbc = new JdbcTemplate(source);
        for (String table : List.of("files", "meter_readings", "outage_records", "operation_metrics")) {
            jdbc.execute("SELECT TOP (0) * INTO #o6_" + table + " FROM dbo." + table);
        }
        // Scope the O6 verification data to this SQL session. The production SQL and JDBC binding are otherwise identical.
        repository = new StatsRepository(new JdbcTemplate(source) {
            @Override
            public <T> T execute(String sql, PreparedStatementCallback<T> action) {
                return super.execute(TABLE.matcher(sql).replaceAll("#o6_$1"), action);
            }
        });
    }

    @AfterEach
    void disconnect() {
        if (source != null) {
            source.destroy();
        }
    }

    @Test
    void emptyDatasetsRetainTotalsAndSqlNulls() {
        var page = repository.anomalies(FROM, TO, 99, 20);
        assertThat(page.items()).isEmpty();
        assertThat(page.totalItems()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(repository.silentMeters(TO, 60)).isEmpty();
        assertThat(repository.consumptionByHour(FROM, TO)).isEmpty();

        var outages = repository.outageZones(FROM, TO);
        assertThat(outages.ranking()).hasSize(3).allSatisfy(zone -> {
            assertThat(zone.rank()).isEqualTo(1);
            assertThat(zone.outages()).isZero();
            assertThat(zone.sharePercent()).isNull();
            assertThat(zone.vsAverageRatio()).isNull();
            assertThat(zone.peakHour()).isNull();
        });
        assertThat(outages.byHour()).isEmpty();
        assertThat(repository.peakVsNormal("normal", "storm", FROM, TO)).hasSize(8).allSatisfy(row -> {
            assertThat(row.operations()).isZero();
            assertThat(row.latencyP95Ms()).isNull();
        });
        assertThat(repository.storageScores(FROM, TO)).hasSize(12).allSatisfy(row -> {
            assertThat(row.score().samples()).isZero();
            assertThat(row.score().score()).isNull();
            assertThat(row.rank()).isNull();
        });
    }

    @Test
    void anomalyPagesUseTheIndependentSqlCount() throws IOException {
        networkFixture();
        var first = repository.anomalies(FROM, TO, 1, 1);
        var second = repository.anomalies(FROM, TO, 2, 1);
        var beyond = repository.anomalies(FROM, TO, Integer.MAX_VALUE, 1);
        assertThat(first.items()).extracting(MeterAnomaly::meterId).containsExactly("even");
        assertThat(second.items()).extracting(MeterAnomaly::meterId).containsExactly("spike");
        assertThat(first.items().getFirst().median()).isEqualTo(1.5);
        assertThat(first.items().getFirst().kwh()).isEqualByComparingTo("12");
        assertThat(first.items().getFirst().readingTs()).isEqualTo("2026-11-14T04:45:00Z");
        assertThat(beyond.items()).isEmpty();
        assertThat(List.of(first, second, beyond)).allSatisfy(page -> {
            assertThat(page.totalItems()).isEqualTo(2);
            assertThat(page.totalPages()).isEqualTo(2);
        });
    }

    @Test
    void silenceUsesReceiptTimeAndHonorsThe100NanosecondBoundary() throws IOException {
        networkFixture();
        var idle = repository.silentMeters(TO.plusNanos(100), 60);
        assertThat(idle).extracting(SilentMeter::meterId).containsExactly("flat", "even", "old", "spike", "over");
        assertThat(idle.get(2)).isEqualTo(new SilentMeter("old", "Banat",
                Instant.parse("2026-11-14T04:40:00Z"), 70));
        assertThat(idle.getLast().silentMinutes()).isEqualTo(60);
        assertThat(repository.silentMeters(TO, 60)).extracting(SilentMeter::meterId)
                .containsExactly("flat", "even", "old", "spike");
    }

    @Test
    void hourlyConsumptionUsesConfirmedReadingsAndExclusiveEnd() throws IOException {
        networkFixture();
        var hours = repository.consumptionByHour(FROM, TO);
        assertThat(hours).hasSize(12);
        assertThat(hours.stream().mapToLong(row -> row.readings()).sum()).isEqualTo(45);
        assertThat(hours.stream().map(row -> row.totalKwh()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("87");
        assertThat(hours.getLast().hour()).isEqualTo("2026-11-14T05:00:00Z");
    }

    @Test
    void outageRankingHandlesTiesAndCountsOutagesWithoutAStation() throws IOException {
        networkFixture();
        var result = repository.outageZones(Instant.parse("2026-11-13T08:00:00Z"), Instant.parse("2026-11-14T10:00:00Z"));
        assertThat(result.ranking()).extracting(ZoneRank::zone).containsExactly("Dobrogea", "Muntenia", "Banat");
        assertThat(result.ranking()).extracting(ZoneRank::rank).containsExactly(1L, 1L, 2L);
        var muntenia = result.ranking().get(1);
        assertThat(muntenia.peakHour()).isEqualTo(8);
        assertThat(muntenia.sharePercent()).isEqualByComparingTo("50");
        assertThat(muntenia.vsAverageRatio()).isEqualByComparingTo("1.5");
        assertThat(result.ranking().getLast().peakHour()).isNull();
        assertThat(result.byHour()).containsExactly(new ZoneHourOutages("Dobrogea", 8, 2),
                new ZoneHourOutages("Muntenia", 8, 1), new ZoneHourOutages("Muntenia", 9, 1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentInsertAfterMaterializationDoesNotBlockOrChangeEitherResult(boolean empty) throws Exception {
        if (!empty) {
            networkFixture();
        }
        Instant from = Instant.parse("2026-11-13T08:00:00Z");
        Instant to = Instant.parse("2026-11-14T10:00:00Z");
        var expected = repository.outageZones(from, to);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String outages = "##o7_outages_" + suffix;
        String gate = "##o7_gate_" + suffix;
        jdbc.execute("SELECT * INTO " + outages + " FROM #o6_outage_records");
        jdbc.execute("CREATE TABLE " + gate + " (state int NOT NULL)");
        jdbc.update("INSERT INTO " + gate + " VALUES (0)");
        var readerSource = new SingleConnectionDataSource(source.getUrl(), "filegrid_meta_app", password(), true);
        try {
            var concurrentRepository = new StatsRepository(new JdbcTemplate(readerSource) {
                @Override
                public <T> T execute(String sql, PreparedStatementCallback<T> action) {
                    // Pause the SQL batch after materialization, before either SELECT executes.
                    String barrier = """
                            UPDATE %s SET state = 1;
                            DECLARE @deadline datetime2 = DATEADD(second, 15, SYSUTCDATETIME());
                            WHILE (SELECT state FROM %s) = 1
                            BEGIN
                                IF SYSUTCDATETIME() >= @deadline
                                    THROW 50000, 'Concurrent test writer did not release the reader.', 1;
                                WAITFOR DELAY '00:00:00.010';
                            END;
                            """.formatted(gate, gate);
                    return super.execute(sql.replace("dbo.outage_records", outages)
                            .replace("-- First result set:", barrier + "\n-- First result set:"), action);
                }
            });
            try (var executor = Executors.newSingleThreadExecutor()) {
                var result = executor.submit(() -> concurrentRepository.outageZones(from, to));
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (jdbc.queryForObject("SELECT state FROM " + gate, Integer.class) == 0
                            && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                    }
                    assertThat(jdbc.queryForObject("SELECT state FROM " + gate, Integer.class)).isEqualTo(1);
                    jdbc.execute("SET LOCK_TIMEOUT 1000");
                    jdbc.update("INSERT INTO " + outages + " (file_id, zone, station_id, occurred_at)"
                            + " VALUES (NEWID(), N'Banat', NULL, '2026-11-14T09:30:00')");
                } finally {
                    jdbc.update("UPDATE " + gate + " SET state = 2");
                    jdbc.execute("SET LOCK_TIMEOUT -1");
                }
                var observed = result.get(10, TimeUnit.SECONDS);
                assertThat(observed).isEqualTo(expected);
                for (ZoneRank zone : observed.ranking()) {
                    assertThat(observed.byHour().stream().filter(hour -> hour.zone().equals(zone.zone()))
                            .mapToLong(ZoneHourOutages::outages).sum()).isEqualTo(zone.outages());
                }
                // The concurrent insert committed while the reader was paused, and remains available to later reads.
                assertThat(jdbc.queryForObject("SELECT COUNT_BIG(*) FROM " + outages
                        + " WHERE occurred_at >= ? AND occurred_at < ?", Long.class,
                        java.time.LocalDateTime.ofInstant(from, java.time.ZoneOffset.UTC),
                        java.time.LocalDateTime.ofInstant(to, java.time.ZoneOffset.UTC)))
                        .isEqualTo(expected.ranking().stream().mapToLong(ZoneRank::outages).sum() + 1);
            }
        } finally {
            readerSource.destroy();
            jdbc.execute("DROP TABLE " + gate);
            jdbc.execute("DROP TABLE " + outages);
        }
    }

    @Test
    void performanceQueriesReuseO6FiltersAndScoresOnSyntheticTestInputs() throws IOException {
        jdbc.execute(Files.readString(ROOT.resolve("db/seed/o6_operation_metrics_fixture.sql")));
        Instant end = Instant.parse("2026-11-14T11:20:00Z");
        var rows = repository.peakVsNormal("normal", "peak", Instant.parse("2026-11-14T10:00:00Z"), end);
        var peak = rows.stream().filter(row -> row.category() == Category.METER_READING && row.scenario().equals("peak"))
                .findFirst().orElseThrow();
        assertThat(peak.operations()).isEqualTo(3);
        assertThat(peak.operationsPerMinute()).isCloseTo(3.0 / 80, within(1e-8));
        assertThat(peak.latencyP95Ms()).isCloseTo(546, within(1e-8));

        var scores = repository.storageScores(FROM, end);
        var ftp = scores.stream().filter(row -> row.score().category() == Category.OUTAGE_RECORD
                && row.score().storage() == StorageType.FTP).findFirst().orElseThrow();
        assertThat(ftp.score().score()).isEqualByComparingTo("100");
        assertThat(ftp.score().relativeCostPerGb()).isEqualByComparingTo("1");
        assertThat(ftp.score().samples()).isEqualTo(1);
        assertThat(ftp.benchmarkSamples()).isZero();
        assertThat(ftp.liveSamples()).isEqualTo(1);
        assertThat(ftp.rank()).isEqualTo(1);

        var providers = new StaticListableBeanFactory();
        providers.addBean("statsRepository", repository);
        var service = new StatsService(providers.getBeanProvider(StatsRepository.class),
                new PolicyEngine("classpath:/policies/", java.time.Duration.ofSeconds(2)));
        assertThatThrownBy(() -> service.storageAdvisor(end)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.details()).containsEntry("reason", "STORAGE_MEASUREMENTS_INCOMPLETE"));

        // Complete the synthetic matrix within the same connection. No fixture is written to FileGridMeta.
        jdbc.execute("""
                INSERT INTO #o6_operation_metrics (id, category, storage, operation, latency_ms, ok, ts, source)
                SELECT NEWID(), category, storage, N'upload', 100, 1, '2026-11-14T11:10:00', N'benchmark'
                FROM (VALUES (N'METER_READING'), (N'OUTAGE_RECORD'), (N'CONNECTION_DOC'), (N'INSPECTION_VIDEO')) c(category)
                CROSS JOIN (VALUES (N'FTP'), (N'S3'), (N'DB')) s(storage)
                """);
        var advisor = service.storageAdvisor(end);
        assertThat(advisor.matrix()).hasSize(12).allSatisfy(cell -> {
            assertThat(cell.score()).isNotNull();
            assertThat(cell.benchmarked()).isTrue();
        });
        assertThat(advisor.basis().benchmarkSamples()).isEqualTo(15);
        assertThat(advisor.recommendations()).hasSize(4);
        var video = advisor.recommendations().stream()
                .filter(recommendation -> recommendation.category() == Category.INSPECTION_VIDEO)
                .findFirst().orElseThrow();
        assertThat(video.recommendedStorage()).isEqualTo(StorageType.FTP);
        assertThat(video.matchesPolicy()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 11})
    void liveScoresCannotReplaceMissingBenchmarkCells(int benchmarkCells) {
        insertStorageMatrix("live", 12);
        insertStorageMatrix("benchmark", benchmarkCells);
        var end = Instant.parse("2026-11-14T12:00:00Z");
        var measurements = repository.storageScores(FROM, end);
        assertThat(measurements).hasSize(12).allSatisfy(cell -> assertThat(cell.score().score()).isNotNull());
        assertThat(measurements.stream().filter(cell -> cell.benchmarkSamples() > 0).count())
                .isEqualTo(benchmarkCells);
        assertThatThrownBy(() -> storageService().storageAdvisor(end)).isInstanceOfSatisfying(ApiException.class,
                e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.details()).containsEntry("reason", "STORAGE_MEASUREMENTS_INCOMPLETE");
                });
    }

    @Test
    void completeBenchmarkMatrixNeedsNoMinimumLiveCount() {
        insertStorageMatrix("benchmark", 12);
        var advisor = storageService().storageAdvisor(Instant.parse("2026-11-14T12:00:00Z"));
        assertThat(advisor.matrix()).hasSize(12).allSatisfy(cell -> {
            assertThat(cell.benchmarked()).isTrue();
            assertThat(cell.samples()).isEqualTo(1);
            assertThat(cell.score()).isNotNull();
        });
        assertThat(advisor.basis().benchmarkSamples()).isEqualTo(12);
        assertThat(advisor.basis().liveSamples()).isZero();
        assertThat(advisor.recommendations()).hasSize(4);
    }

    private StatsService storageService() {
        var providers = new StaticListableBeanFactory();
        providers.addBean("statsRepository", repository);
        return new StatsService(providers.getBeanProvider(StatsRepository.class),
                new PolicyEngine("classpath:/policies/", java.time.Duration.ofSeconds(2)));
    }

    private void insertStorageMatrix(String source, int cells) {
        jdbc.update("""
                INSERT INTO #o6_operation_metrics (id, category, storage, operation, latency_ms, ok, ts, source)
                SELECT TOP (?) NEWID(), category, storage, N'upload', 100, 1, '2026-11-14T11:10:00', ?
                FROM (VALUES (N'METER_READING'), (N'OUTAGE_RECORD'), (N'CONNECTION_DOC'), (N'INSPECTION_VIDEO')) c(category)
                CROSS JOIN (VALUES (N'FTP'), (N'S3'), (N'DB')) s(storage)
                ORDER BY category, storage
                """, cells, source);
    }

    private void networkFixture() throws IOException {
        jdbc.execute(Files.readString(ROOT.resolve("db/analytics-queries/verification/network_fixture.sql")));
    }

    private static String password() throws IOException {
        String password = System.getenv("META_DB_PASSWORD");
        if (password != null && !password.isBlank()) {
            return password;
        }
        try (var lines = Files.lines(ROOT.resolve(".env"))) {
            return lines.filter(line -> line.startsWith("META_DB_PASSWORD=")).findFirst()
                    .map(line -> line.substring("META_DB_PASSWORD=".length()).strip())
                    .orElseThrow(() -> new IllegalStateException("Set META_DB_PASSWORD for the SQL integration test"));
        }
    }
}
