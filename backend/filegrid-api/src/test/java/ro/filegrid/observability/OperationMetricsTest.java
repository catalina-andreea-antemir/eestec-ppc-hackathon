package ro.filegrid.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import ro.filegrid.api.ApiException;
import ro.filegrid.api.RequestScenario;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.resilience.StorageCircuitOpenException;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageUnavailableException;
import ro.filegrid.storage.db.OperationMetric;

class OperationMetricsTest {

    private final PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final List<OperationMetric> recorded = Collections.synchronizedList(new ArrayList<>());
    private final OperationRecorder recorder = new OperationRecorder(recorded::addAll, new SimpleMeterRegistry(), 100, 1, 20);
    private final OperationMetrics metrics = new OperationMetrics(prometheus, new SingleProvider(recorder));

    @Test
    void successRecordsTimerWithHistogramAndExactLabels() {
        metrics.run(Category.OUTAGE_RECORD, StorageType.S3, OperationMetrics.UPLOAD, 5L, () -> { });

        Timer timer = prometheus.get("filegrid.operation").tags("category", "OUTAGE_RECORD", "storage", "S3",
                "operation", "upload", "outcome", "ok").timer();
        assertThat(timer.count()).isEqualTo(1);
        String scrape = prometheus.scrape();
        assertThat(scrape).contains("filegrid_operation_seconds_bucket{category=\"OUTAGE_RECORD\"")
                .contains("filegrid_operation_seconds_count{").contains("filegrid_operation_seconds_sum{");
        assertThat(prometheus.find("filegrid.operation.errors").counter()).isNull();
    }

    @Test
    void storageFailureCountsErrorAndFailedTransfer() {
        assertThatThrownBy(() -> metrics.run(Category.INSPECTION_VIDEO, StorageType.FTP, OperationMetrics.UPLOAD_CHUNK,
                10L, () -> {
                    throw new StorageUnavailableException(StorageType.FTP, "cade");
                })).isInstanceOf(StorageUnavailableException.class);

        assertThat(prometheus.get("filegrid.operation.errors").tags("category", "INSPECTION_VIDEO", "storage", "FTP",
                "operation", "upload_chunk", "error", "storage_unavailable").counter().count()).isEqualTo(1);
        assertThat(prometheus.get("filegrid.transfers.failed").tags("category", "INSPECTION_VIDEO", "storage", "FTP")
                .counter().count()).isEqualTo(1);
        assertThat(prometheus.get("filegrid.operation").tags("outcome", "error").timer().count()).isEqualTo(1);
    }

    @Test
    void circuitOpenIsRejectedAndNonTransferOpsDoNotCountAsFailedTransfers() {
        assertThatThrownBy(() -> metrics.run(Category.METER_READING, StorageType.DB, OperationMetrics.DELETE, null,
                () -> {
                    throw new StorageCircuitOpenException(StorageType.DB, new RuntimeException("deschis"));
                })).isInstanceOf(StorageCircuitOpenException.class);

        assertThat(prometheus.get("filegrid.operation").tags("outcome", "rejected").timer().count()).isEqualTo(1);
        assertThat(prometheus.get("filegrid.operation.errors").tags("error", "circuit_open").counter().count())
                .isEqualTo(1);
        assertThat(prometheus.find("filegrid.transfers.failed").counter()).isNull();
    }

    @Test
    void classifiesErrorsWithTheDocumentedValues() {
        assertThat(OperationMetrics.classify(new ApiException(HttpStatus.CONFLICT, "FILE_CORRUPT", "x")))
                .isEqualTo("integrity");
        assertThat(OperationMetrics.classify(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "x")))
                .isEqualTo("storage_unavailable");
        assertThat(OperationMetrics.classify(new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "x"))).isNull();
        assertThat(OperationMetrics.classify(new StorageException(StorageType.S3, "x",
                new java.net.SocketTimeoutException("t")))).isEqualTo("timeout");
        assertThat(OperationMetrics.classify(new IllegalStateException("x"))).isEqualTo("internal");
    }

    @Test
    void recordsOperationMetricsRowWithScenarioFromTheRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestScenario.ATTRIBUTE, "peak");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            metrics.run(Category.CONNECTION_DOC, StorageType.DB, OperationMetrics.DOWNLOAD, 1024L, () -> { });
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        org.awaitility.Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> !recorded.isEmpty());
        OperationMetric row = recorded.getFirst();
        assertThat(row.category()).isEqualTo("CONNECTION_DOC");
        assertThat(row.storage()).isEqualTo("DB");
        assertThat(row.operation()).isEqualTo("download");
        assertThat(row.source()).isEqualTo("live");
        assertThat(row.scenario()).isEqualTo("peak");
        assertThat(row.sizeBytes()).isEqualTo(1024L);
        assertThat(row.ok()).isTrue();
        recorder.shutdown();
    }

    @Test
    void integrityAndDuplicateCountersUseTheFixedLabels() {
        metrics.integrityFailure(Category.CONNECTION_DOC, StorageType.DB);
        metrics.duplicate(Category.METER_READING);
        assertThat(prometheus.get("filegrid.integrity.failures").tags("category", "CONNECTION_DOC", "storage", "DB")
                .counter().count()).isEqualTo(1);
        assertThat(prometheus.get("filegrid.meter.duplicates").tag("category", "METER_READING").counter().count())
                .isEqualTo(1);
        assertThat(prometheus.scrape()).contains("filegrid_integrity_failures_total{")
                .contains("filegrid_meter_duplicates_total{");
    }

    /** ObjectProvider minimal pentru test. */
    private record SingleProvider(OperationRecorder value)
            implements org.springframework.beans.factory.ObjectProvider<OperationRecorder> {
        @Override
        public OperationRecorder getObject(Object... args) {
            return value;
        }

        @Override
        public OperationRecorder getObject() {
            return value;
        }

        @Override
        public OperationRecorder getIfAvailable() {
            return value;
        }

        @Override
        public OperationRecorder getIfUnique() {
            return value;
        }
    }
}
