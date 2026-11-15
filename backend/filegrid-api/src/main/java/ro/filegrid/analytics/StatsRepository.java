package ro.filegrid.analytics;

import static ro.filegrid.analytics.StatsResponses.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.observability.OperationMetrics;

@Repository
@Profile("db")
class StatsRepository {

    private final JdbcTemplate jdbc;

    StatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    AnomalyPage anomalies(Instant from, Instant to, int page, int size) {
        return execute(AnalyticsSql.ANOMALIES, (statement, first) -> {
            var items = rows(first, (row, n) -> new MeterAnomaly(row.getString("meter_id"), row.getString("zone"),
                    instant(row, "reading_ts"), row.getBigDecimal("kwh"), row.getDouble("median_kwh"),
                    row.getDouble("stddev_kwh"), row.getDouble("threshold_kwh"), row.getDouble("deviations")));
            try (ResultSet count = resultSet(statement, statement.getMoreResults())) {
                if (!count.next()) {
                    throw new SQLException("Missing anomaly count row");
                }
                long total = count.getLong("total_items");
                return new AnomalyPage(items, page, size, total, Math.ceilDiv(total, size));
            }
        }, utc(to), utc(from), page, size);
    }

    List<SilentMeter> silentMeters(Instant asOf, int threshold) {
        return query(AnalyticsSql.SILENT_METERS, (row, n) -> new SilentMeter(row.getString("meter_id"),
                row.getString("zone"), instant(row, "last_reading_ts"), row.getLong("silent_minutes")),
                utc(asOf), threshold);
    }

    List<ZoneHourReadings> consumptionByHour(Instant from, Instant to) {
        return query(AnalyticsSql.CONSUMPTION_BY_HOUR, (row, n) -> new ZoneHourReadings(row.getString("zone"),
                instant(row, "hour_utc"), row.getLong("readings"), row.getBigDecimal("total_kwh")), utc(to), utc(from));
    }

    // O6 materializes the shared aggregates before producing either result set.
    Outages outageZones(Instant from, Instant to) {
        return execute(AnalyticsSql.OUTAGE_ZONES, (statement, first) -> {
            var ranking = rows(first, (row, n) -> new ZoneRank(row.getLong("rank"), row.getString("zone"),
                    row.getLong("outages"), row.getBigDecimal("share_percent"), row.getBigDecimal("vs_average_ratio"),
                    row.getObject("peak_hour_utc", Integer.class)));
            try (ResultSet distribution = resultSet(statement, statement.getMoreResults())) {
                var byHour = rows(distribution, (row, n) -> new ZoneHourOutages(row.getString("zone"),
                        row.getInt("hour_utc"), row.getLong("outages")));
                return new Outages(ranking, byHour);
            }
        }, utc(to), utc(from));
    }

    List<OperationStats> peakVsNormal(String normal, String peak, Instant from, Instant to) {
        return peakVsNormal(normal, from, to, peak, from, to);
    }

    /** O6 query 05 with one window per scenario; rows with operation {@code rejected} are excluded by the query. */
    List<OperationStats> peakVsNormal(String normal, Instant normalFrom, Instant normalTo,
                                      String peak, Instant peakFrom, Instant peakTo) {
        return query(AnalyticsSql.PEAK_VS_NORMAL, (row, n) -> new OperationStats(
                Category.valueOf(row.getString("category")), row.getString("scenario"), row.getLong("operations"),
                row.getDouble("operations_per_minute"), row.getObject("latency_p50_ms", Double.class),
                row.getObject("latency_p95_ms", Double.class), row.getObject("latency_p99_ms", Double.class),
                row.getBigDecimal("error_rate_percent"), row.getObject("p95_change_percent", Double.class)),
                normal, peak, utc(normalTo), utc(normalFrom), utc(peakTo), utc(peakFrom));
    }

    /** First/last live row and bulkhead rejections per scenario and category, in {@code [from, to)}. */
    List<ScenarioActivity> scenarioActivity(String normal, String peak, Instant from, Instant to) {
        return query(SCENARIO_ACTIVITY, (row, n) -> new ScenarioActivity(row.getString("scenario"),
                Category.valueOf(row.getString("category")), instant(row, "first_ts"), instant(row, "last_ts"),
                row.getLong("rejected")), OperationMetrics.BULKHEAD_REJECTED, normal, peak, utc(from), utc(to));
    }

    private static final String SCENARIO_ACTIVITY = """
            SELECT scenario, category, MIN(ts) AS first_ts, MAX(ts) AS last_ts,
                   COUNT_BIG(CASE WHEN operation = ? THEN 1 END) AS rejected
            FROM dbo.operation_metrics
            WHERE source = N'live' AND scenario IN (?, ?) AND ts >= ? AND ts < ?
            GROUP BY scenario, category""";

    List<StorageMeasurement> storageScores(Instant from, Instant to) {
        CostAssumption costs = AnalyticsSql.COSTS;
        return query(AnalyticsSql.STORAGE_ADVISOR, (row, n) -> {
            long benchmark = row.getLong("benchmark_samples");
            return new StorageMeasurement(new StorageScore(Category.valueOf(row.getString("category")),
                    StorageType.valueOf(row.getString("storage")), row.getBigDecimal("score"),
                    row.getObject("latency_p50_ms", Double.class), row.getObject("latency_p95_ms", Double.class),
                    row.getObject("error_rate_percent", Double.class), row.getBigDecimal("relative_cost_per_gb"),
                    row.getLong("samples"), row.getBoolean("benchmarked")), row.getObject("rank", Long.class),
                    benchmark, row.getLong("live_samples"));
        }, utc(to), utc(from), "upload", costs.FTP(), costs.S3(), costs.DB());
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) {
        return execute(sql, (statement, result) -> rows(result, mapper), parameters);
    }

    private <T> T execute(String sql, ResultReader<T> reader, Object... parameters) {
        return jdbc.execute(sql, (PreparedStatement statement) -> {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet result = resultSet(statement, statement.execute())) {
                return reader.read(statement, result);
            }
        });
    }

    private static ResultSet resultSet(PreparedStatement statement, boolean hasResult) throws SQLException {
        while (!hasResult && statement.getUpdateCount() != -1) {
            hasResult = statement.getMoreResults();
        }
        if (!hasResult) {
            throw new SQLException("Missing analytics result set");
        }
        return statement.getResultSet();
    }

    private static <T> List<T> rows(ResultSet result, RowMapper<T> mapper) throws SQLException {
        List<T> values = new ArrayList<>();
        while (result.next()) {
            values.add(mapper.mapRow(result, values.size()));
        }
        return values;
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    record Outages(List<ZoneRank> ranking, List<ZoneHourOutages> byHour) {
    }

    record ScenarioActivity(String scenario, Category category, Instant firstTs, Instant lastTs, long rejected) {
    }

    record StorageMeasurement(StorageScore score, Long rank, long benchmarkSamples, long liveSamples) {
    }

    @FunctionalInterface
    private interface ResultReader<T> {
        T read(PreparedStatement statement, ResultSet result) throws SQLException;
    }
}
