package ro.filegrid.storage.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OperationMetricsRepositoryTest {

    private static final BigDecimal LATENCY = new BigDecimal("12.345678");
    private static final OperationMetric LIVE_METRIC = new OperationMetric(
            "METER_READING", "DB", "upload", LATENCY, true, 1024L, "live", "normal");

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final OperationMetricsRepository repository = new OperationMetricsRepository(jdbc, transactions);

    @Test
    void insertsAllRowsInOneBatchWithDatabaseGeneratedIdAndTimestamp() {
        var transaction = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(transaction);
        var benchmarkMetric = new OperationMetric("INSPECTION_VIDEO", "S3", "download",
                BigDecimal.ZERO, false, 2048L, "benchmark", "benchmark");

        repository.insertBatch(List.of(LIVE_METRIC, benchmarkMetric));

        var sql = ArgumentCaptor.forClass(String.class);
        var rows = ArgumentCaptor.forClass(SqlParameterSource[].class);
        var order = inOrder(transactions, jdbc);
        order.verify(transactions).getTransaction(any());
        order.verify(jdbc).batchUpdate(sql.capture(), rows.capture());
        order.verify(transactions).commit(transaction);
        order.verifyNoMoreInteractions();

        assertThat(sql.getValue().strip()).isEqualTo("""
                INSERT INTO dbo.operation_metrics (category, storage, operation, latency_ms, ok, size_bytes, source, scenario)
                VALUES (:category, :storage, :operation, :latencyMs, :ok, :sizeBytes, :source, :scenario)
                """.strip());
        assertThat(rows.getValue()).hasSize(2);
        assertRow(rows.getValue()[0], LIVE_METRIC);
        assertThat(rows.getValue()[0].getValue("latencyMs")).isEqualTo(new BigDecimal("12.346"));
        assertRow(rows.getValue()[1], benchmarkMetric);
    }

    @ParameterizedTest
    @CsvSource({
            "0.0001, 0.000",
            "12.344499, 12.344",
            "12.3445, 12.345",
            "999999999999999.999499, 999999999999999.999"
    })
    void roundsLatencyHalfUpToThreeDecimals(String measurement, String expected) {
        var metric = new OperationMetric("METER_READING", "DB", "upload", new BigDecimal(measurement),
                true, null, "live", null);

        assertThat(metric.latencyMs()).isEqualByComparingTo(expected);
        assertThat(metric.latencyMs().scale()).isEqualTo(3);
    }

    @Test
    void skipsJdbcAndTransactionsForAnEmptyBatch() {
        repository.insertBatch(List.of());

        verifyNoInteractions(jdbc, transactions);
    }

    @Test
    void bindsMissingSizeAndScenarioAsTypedNulls() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var metric = new OperationMetric("OUTAGE_RECORD", "FTP", "verify", LATENCY, false, null, "live", null);

        repository.insertBatch(List.of(metric));

        var rows = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbc).batchUpdate(anyString(), rows.capture());
        assertThat(rows.getValue()).hasSize(1);
        var row = rows.getValue()[0];
        assertRow(row, metric);
        assertThat(row.getSqlType("sizeBytes")).isEqualTo(Types.BIGINT);
        assertThat(row.getSqlType("scenario")).isEqualTo(Types.NVARCHAR);
    }

    @Test
    void rejectsNullBatchesAndNullRowsBeforeStartingATransaction() {
        assertThatThrownBy(() -> repository.insertBatch(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.insertBatch(Arrays.asList(LIVE_METRIC, null)))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(jdbc, transactions);
    }

    @ParameterizedTest
    @MethodSource("invalidMetrics")
    void rejectsMetricsOutsideTheSqlColumnConstraints(String field, String category, String storage, String operation,
                                                        BigDecimal latency, Long size, String source, String scenario) {
        assertThatThrownBy(() -> new OperationMetric(category, storage, operation, latency, true, size, source, scenario))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(field);
    }

    @Test
    void rollsBackTheBatchAndPropagatesTheDatabaseError() {
        var transaction = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(transaction);
        var failure = new DataAccessResourceFailureException("Batch insert failed");
        when(jdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenThrow(failure);

        assertThatThrownBy(() -> repository.insertBatch(List.of(LIVE_METRIC))).isSameAs(failure);

        var order = inOrder(transactions, jdbc);
        order.verify(transactions).getTransaction(any());
        order.verify(jdbc).batchUpdate(anyString(), any(SqlParameterSource[].class));
        order.verify(transactions).rollback(transaction);
        order.verifyNoMoreInteractions();
    }

    private static Stream<Arguments> invalidMetrics() {
        return Stream.of(
                Arguments.of("category", "UNKNOWN", "DB", "upload", LATENCY, 1L, "live", null),
                Arguments.of("storage", "METER_READING", "LOCAL", "upload", LATENCY, 1L, "live", null),
                Arguments.of("operation", "METER_READING", "DB", null, LATENCY, 1L, "live", null),
                Arguments.of("operation", "METER_READING", "DB", "", LATENCY, 1L, "live", null),
                Arguments.of("operation", "METER_READING", "DB", " \t\n", LATENCY, 1L, "live", null),
                Arguments.of("operation", "METER_READING", "DB", "u".repeat(65), LATENCY, 1L, "live", null),
                Arguments.of("latencyMs", "METER_READING", "DB", "upload", null, 1L, "live", null),
                Arguments.of("latencyMs", "METER_READING", "DB", "upload", new BigDecimal("-0.0001"), 1L, "live", null),
                Arguments.of("latencyMs", "METER_READING", "DB", "upload", new BigDecimal("1E15"), 1L, "live", null),
                Arguments.of("latencyMs", "METER_READING", "DB", "upload",
                        new BigDecimal("999999999999999.9995"), 1L, "live", null),
                Arguments.of("sizeBytes", "METER_READING", "DB", "upload", LATENCY, 0L, "live", null),
                Arguments.of("sizeBytes", "METER_READING", "DB", "upload", LATENCY, -1L, "live", null),
                Arguments.of("source", "METER_READING", "DB", "upload", LATENCY, 1L, "test", null),
                Arguments.of("scenario", "METER_READING", "DB", "upload", LATENCY, 1L, "live", "s".repeat(129)));
    }

    private static void assertRow(SqlParameterSource row, OperationMetric metric) {
        assertThat(row.getParameterNames()).containsExactlyInAnyOrder(
                "category", "storage", "operation", "latencyMs", "ok", "sizeBytes", "source", "scenario");
        assertThat(row.getValue("category")).isEqualTo(metric.category());
        assertThat(row.getValue("storage")).isEqualTo(metric.storage());
        assertThat(row.getValue("operation")).isEqualTo(metric.operation());
        assertThat(row.getValue("latencyMs")).isEqualTo(metric.latencyMs());
        assertThat(row.getValue("ok")).isEqualTo(metric.ok());
        assertThat(row.getValue("sizeBytes")).isEqualTo(metric.sizeBytes());
        assertThat(row.getValue("source")).isEqualTo(metric.source());
        assertThat(row.getValue("scenario")).isEqualTo(metric.scenario());
    }
}
