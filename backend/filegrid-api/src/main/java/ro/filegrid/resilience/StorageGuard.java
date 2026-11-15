package ro.filegrid.resilience;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.ChunkedWriter;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageHealth;
import ro.filegrid.storage.StorageUnavailableException;

/**
 * Per-storage resilience (C11) on top of the adapters: circuit breaker, retry with exponential backoff and jitter,
 * and a cached health state refreshed periodically in the background (requests never wait for it).
 * {@link ro.filegrid.storage.StorageAdapterRegistry} wraps the adapters with {@link #wrap(StorageAdapter)} /
 * {@link #wrap(ChunkedWriter)}, so all existing code goes through here unchanged.
 *
 * <ul>
 *   <li>Only {@link StorageUnavailableException} counts as a failure and is retried; {@code NotFound} and other
 *       errors do not affect the circuit.</li>
 *   <li>Retry only for operations that have not consumed a body: get / exists / delete / finish / abort.
 *       {@code put}, {@code append} and {@code open} fail immediately (the body is consumed; the client retries).</li>
 *   <li>Open circuit => {@link StorageCircuitOpenException} immediately (503 + Retry-After at the caller).</li>
 *   <li>Periodic check: after {@code healthFailuresToOpen} consecutive failures the circuit opens; in HALF_OPEN the
 *       check is the probe call (success => the circuit closes by itself, failure => it reopens).</li>
 * </ul>
 */
@Component
public class StorageGuard {

    private static final Logger log = LoggerFactory.getLogger(StorageGuard.class);

    private final ResilienceProperties props;
    private final MeterRegistry meters;
    private final Map<StorageType, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "storage-health");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService probes = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "storage-health-probe");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public StorageGuard(ResilienceProperties props, MeterRegistry meters) {
        this.props = props;
        this.meters = meters;
    }

    /** Wraps the adapter and starts its periodic check. */
    public StorageAdapter wrap(StorageAdapter raw) {
        Entry entry = entry(raw.type());
        entry.raw = raw;
        scheduler.scheduleWithFixedDelay(() -> safeCheck(entry), props.healthInterval().toMillis(), props.healthInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        return new ResilientStorageAdapter(raw, this);
    }

    public ChunkedWriter wrap(ChunkedWriter raw) {
        entry(raw.type());
        return new ResilientChunkedWriter(raw, this);
    }

    /** Repeatable operation: retried on unavailability, through the circuit. */
    <T> T read(StorageType type, Supplier<T> op) {
        Entry e = entry(type);
        return Retry.decorateSupplier(e.retry, () -> through(e, op)).get();
    }

    /** Operation that consumes a body (put / append / open): single attempt, through the circuit. */
    <T> T once(StorageType type, Supplier<T> op) {
        return through(entry(type), op);
    }

    private <T> T through(Entry e, Supplier<T> op) {
        try {
            return e.breaker.executeSupplier(op);
        } catch (CallNotPermittedException x) {
            throw new StorageCircuitOpenException(e.type, x);
        }
    }

    /** Cached state; an open circuit means DOWN. Never blocks and never calls the storage. */
    public StorageHealth health(StorageType type) {
        Entry e = entries.get(type);
        if (e == null) {
            return StorageHealth.down(type, Duration.ZERO, "fără adaptor");
        }
        CircuitBreaker.State state = e.breaker.getState();
        if (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN) {
            StorageHealth cached = e.cached;
            return StorageHealth.down(type, cached.latency(), cached.up() ? "circuit deschis" : cached.detail());
        }
        return e.cached;
    }

    /** CLOSED / OPEN / HALF_OPEN. */
    public String circuitState(StorageType type) {
        Entry e = entries.get(type);
        if (e == null) {
            return "CLOSED";
        }
        return switch (e.breaker.getState()) {
            case HALF_OPEN -> "HALF_OPEN";
            case OPEN, FORCED_OPEN -> "OPEN";
            default -> "CLOSED";
        };
    }

    /** Runs the storage check now (tests). */
    void checkNow(StorageType type) {
        Entry e = entries.get(type);
        if (e != null && e.raw != null) {
            safeCheck(e);
        }
    }

    private void safeCheck(Entry e) {
        try {
            check(e);
        } catch (RuntimeException ex) {
            log.warn("Health check of storage {} failed unexpectedly: {}", e.type, ex.toString());
        }
    }

    private void check(Entry e) {
        StorageHealth h;
        Future<StorageHealth> f = probes.submit(e.raw::health);
        try {
            h = f.get(props.healthTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            f.cancel(true);
            h = StorageHealth.down(e.type, props.healthTimeout(), "timeout");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | RuntimeException ex) {
            h = StorageHealth.down(e.type, Duration.ZERO, "verificare eșuată");
        }
        e.cached = h;
        CircuitBreaker.State state = e.breaker.getState();
        if (h.up()) {
            e.failures = 0;
            if (state == CircuitBreaker.State.HALF_OPEN) {
                e.breaker.transitionToClosedState();
                log.info("Storage {} is back; circuit closed", e.type);
            }
            return;
        }
        e.failures++;
        boolean open = state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
        if (!open && (state == CircuitBreaker.State.HALF_OPEN || e.failures >= props.healthFailuresToOpen())) {
            e.breaker.transitionToOpenState();
            log.warn("Storage {} is down ({}); circuit open", e.type, h.detail());
        }
    }

    private Entry entry(StorageType type) {
        return entries.computeIfAbsent(type, this::create);
    }

    private Entry create(StorageType type) {
        CircuitBreaker breaker = CircuitBreaker.of("storage-" + type, CircuitBreakerConfig.custom()
                .failureRateThreshold(props.failureRateThreshold())
                .minimumNumberOfCalls(props.minimumCalls())
                .slidingWindowSize(props.slidingWindow())
                .waitDurationInOpenState(props.openWait())
                .permittedNumberOfCallsInHalfOpenState(props.halfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(StorageGuard::retriable)
                .build());
        Retry retry = Retry.of("storage-" + type, RetryConfig.custom()
                .maxAttempts(props.retryAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(props.retryInitialBackoff(),
                        props.retryMultiplier(), props.retryJitter()))
                .retryOnException(StorageGuard::retriable)
                .build());
        Gauge.builder("filegrid.storage.up", () -> health(type).up() ? 1 : 0).tag("storage", type.name())
                .description("Starea storage-ului: 1 = UP, 0 = DOWN").register(meters);
        return new Entry(type, breaker, retry);
    }

    private static boolean retriable(Throwable t) {
        return t instanceof StorageUnavailableException && !(t instanceof StorageCircuitOpenException);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
        probes.shutdownNow();
    }

    private static final class Entry {
        final StorageType type;
        final CircuitBreaker breaker;
        final Retry retry;
        volatile StorageAdapter raw;
        volatile StorageHealth cached;
        volatile int failures;

        Entry(StorageType type, CircuitBreaker breaker, Retry retry) {
            this.type = type;
            this.breaker = breaker;
            this.retry = retry;
            this.cached = StorageHealth.up(type, Duration.ZERO); // optimistic until the first check
        }
    }
}
