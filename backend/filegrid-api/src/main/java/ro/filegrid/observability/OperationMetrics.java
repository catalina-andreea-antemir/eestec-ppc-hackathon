package ro.filegrid.observability;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import ro.filegrid.api.ApiException;
import ro.filegrid.api.RequestScenario;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.resilience.StorageCircuitOpenException;
import ro.filegrid.storage.StorageUnavailableException;
import ro.filegrid.storage.db.OperationMetric;

/**
 * Punctul unic de instrumentare a operațiilor pe storage (C12, planul 4.3): fiecare apel de storage din servicii trece
 * prin {@link #time}, care înregistrează timerul cu histogramă {@code filegrid_operation_seconds}, contoarele de erori
 * și de transferuri eșuate și, dacă există recorder, rândul pentru {@code operation_metrics} (scenariul vine din
 * {@code X-Scenario}). Etichetele au exact valorile din {@code x-metrics.labelValues} (openapi.yaml).
 */
@Component
public class OperationMetrics {

    public static final String UPLOAD = "upload";
    public static final String UPLOAD_CHUNK = "upload_chunk";
    public static final String UPLOAD_COMPLETE = "upload_complete";
    public static final String DOWNLOAD = "download";
    public static final String VERIFY = "verify";
    public static final String DELETE = "delete";
    /** Operația rândurilor scrise la respingerile de bulkhead; statisticile de latență și erori o exclud. */
    public static final String BULKHEAD_REJECTED = "rejected";

    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String REJECTED = "rejected";

    private static final Set<String> TRANSFERS = Set.of(UPLOAD, UPLOAD_CHUNK, UPLOAD_COMPLETE, DOWNLOAD);

    private final MeterRegistry registry;
    private final ObjectProvider<OperationRecorder> recorder;

    @Autowired
    public OperationMetrics(MeterRegistry registry, ObjectProvider<OperationRecorder> recorder) {
        this.registry = registry;
        this.recorder = recorder;
    }

    /** Fără recorder și cu un registru propriu (teste unitare ale serviciilor). */
    public static OperationMetrics noop() {
        return new OperationMetrics(new SimpleMeterRegistry(), null);
    }

    public <T> T time(Category category, StorageType storage, String operation, Long sizeBytes, Supplier<T> call) {
        long start = System.nanoTime();
        try {
            T result = call.get();
            finish(category, storage, operation, sizeBytes, start, OK, null);
            return result;
        } catch (RuntimeException e) {
            String error = classify(e);
            finish(category, storage, operation, sizeBytes, start, outcomeOf(error), error);
            throw e;
        }
    }

    public void run(Category category, StorageType storage, String operation, Long sizeBytes, Runnable call) {
        time(category, storage, operation, sizeBytes, () -> {
            call.run();
            return null;
        });
    }

    public void integrityFailure(Category category, StorageType storage) {
        Counter.builder("filegrid.integrity.failures").description("Fișiere corupte detectate")
                .tag("category", category.name()).tag("storage", storage.name()).register(registry).increment();
    }

    /**
     * Rândul {@code operation_metrics} pentru o cerere respinsă de bulkhead ({@link #BULKHEAD_REJECTED}, ok=false,
     * latență 0, storage-ul principal al categoriei). Contorul Micrometer îl incrementează {@code CategoryBulkheads};
     * aici nu se înregistrează timer, ca latențele operațiilor reale să rămână neatinse.
     */
    public void bulkheadRejected(Category category, StorageType primaryStorage) {
        OperationRecorder rec = recorder == null ? null : recorder.getIfAvailable();
        if (rec != null) {
            rec.record(new OperationMetric(category.name(), primaryStorage.name(), BULKHEAD_REJECTED, BigDecimal.ZERO,
                    false, null, "live", currentScenario()));
        }
    }

    public void duplicate(Category category) {
        Counter.builder("filegrid.meter.duplicates").description("Citiri retrimise, respinse ca duplicate")
                .tag("category", category.name()).register(registry).increment();
    }

    private void finish(Category category, StorageType storage, String operation, Long sizeBytes, long startNanos,
                        String outcome, String error) {
        long nanos = System.nanoTime() - startNanos;
        Timer.builder("filegrid.operation").description("Durata operațiilor pe storage")
                .tag("category", category.name()).tag("storage", storage.name()).tag("operation", operation)
                .tag("outcome", outcome)
                .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(5))
                .register(registry).record(nanos, TimeUnit.NANOSECONDS);
        if (error != null) {
            Counter.builder("filegrid.operation.errors").description("Operații eșuate pe storage")
                    .tag("category", category.name()).tag("storage", storage.name()).tag("operation", operation)
                    .tag("error", error).register(registry).increment();
            if (TRANSFERS.contains(operation)) {
                Counter.builder("filegrid.transfers.failed").description("Transferuri eșuate")
                        .tag("category", category.name()).tag("storage", storage.name()).register(registry)
                        .increment();
            }
        }
        OperationRecorder rec = recorder == null ? null : recorder.getIfAvailable();
        if (rec != null) {
            rec.record(new OperationMetric(category.name(), storage.name(), operation,
                    BigDecimal.valueOf(nanos).movePointLeft(6), error == null, // 4xx ale clientului nu sunt eșecuri de storage
                    sizeBytes != null && sizeBytes > 0 ? sizeBytes : null, "live", currentScenario()));
        }
    }

    /** {@code rejected} pentru circuit deschis și 4xx ale clientului (error null); {@code error} pentru restul. */
    static String outcomeOf(String error) {
        return error == null || "circuit_open".equals(error) ? REJECTED : ERROR;
    }

    /**
     * Valoarea etichetei {@code error}. {@code null} înseamnă un 4xx al clientului (validare, Range invalid), care nu
     * e o eroare de storage și nu se numără în contoare.
     */
    static String classify(Throwable e) {
        if (e instanceof StorageCircuitOpenException) {
            return "circuit_open";
        }
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
                return "timeout";
            }
        }
        if (e instanceof StorageUnavailableException) {
            return "storage_unavailable";
        }
        if (e instanceof ApiException a) {
            if ("FILE_CORRUPT".equals(a.code())) {
                return "integrity";
            }
            if ("STORAGE_UNAVAILABLE".equals(a.code())) {
                return "storage_unavailable";
            }
            if (a.status().is4xxClientError() && a.status() != HttpStatus.CONFLICT) {
                return null;
            }
        }
        return "internal";
    }

    private static String currentScenario() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object value = attributes == null ? null
                : attributes.getAttribute(RequestScenario.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof String s ? s : null;
    }
}
