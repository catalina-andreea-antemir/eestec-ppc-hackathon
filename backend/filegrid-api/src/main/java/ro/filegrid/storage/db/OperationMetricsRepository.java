package ro.filegrid.storage.db;

import java.sql.Types;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("db")
public class OperationMetricsRepository {

    private static final String INSERT_METRIC = """
            INSERT INTO dbo.operation_metrics (category, storage, operation, latency_ms, ok, size_bytes, source, scenario)
            VALUES (:category, :storage, :operation, :latencyMs, :ok, :sizeBytes, :source, :scenario)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public OperationMetricsRepository(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void insertBatch(List<OperationMetric> metrics) {
        List<OperationMetric> batch = List.copyOf(metrics);
        if (batch.isEmpty()) {
            return;
        }

        SqlParameterSource[] parameters = batch.stream()
                .map(metric -> new MapSqlParameterSource()
                        .addValue("category", metric.category(), Types.NVARCHAR)
                        .addValue("storage", metric.storage(), Types.NVARCHAR)
                        .addValue("operation", metric.operation(), Types.NVARCHAR)
                        .addValue("latencyMs", metric.latencyMs(), Types.DECIMAL)
                        .addValue("ok", metric.ok(), Types.BIT)
                        .addValue("sizeBytes", metric.sizeBytes(), Types.BIGINT)
                        .addValue("source", metric.source(), Types.NVARCHAR)
                        .addValue("scenario", metric.scenario(), Types.NVARCHAR))
                .toArray(SqlParameterSource[]::new);
        transaction.executeWithoutResult(status -> jdbc.batchUpdate(INSERT_METRIC, parameters));
    }
}
