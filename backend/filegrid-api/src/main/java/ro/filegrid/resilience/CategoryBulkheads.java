package ro.filegrid.resilience;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.observability.OperationMetrics;
import ro.filegrid.policy.PolicyEngine;

/**
 * Per-category isolation (C9): one {@link Bulkhead} per category, sized from the policy's
 * {@code maxConcurrentRequests} (per instance, hot-reloaded). Saturation returns 429 {@code CATEGORY_SATURATED} with
 * {@code Retry-After} for the saturated category only, increments {@code filegrid_bulkhead_rejected_total{category}}
 * and writes a {@code rejected} row to {@code operation_metrics} (with the {@code X-Scenario} value), which
 * {@code /stats/peak-vs-normal} uses to count rejections.
 */
@Component
public class CategoryBulkheads {

    /** Seconds until retry; permits free up within seconds. */
    static final int RETRY_AFTER_SECONDS = 1;

    private final PolicyEngine policies;
    private final OperationMetrics metrics;
    private final Map<Category, Bulkhead> bulkheads = new EnumMap<>(Category.class);
    private final Map<Category, Counter> rejected = new EnumMap<>(Category.class);

    /** Without {@code operation_metrics} rows (unit tests). */
    public CategoryBulkheads(PolicyEngine policies, MeterRegistry registry) {
        this(policies, registry, OperationMetrics.noop());
    }

    @Autowired
    public CategoryBulkheads(PolicyEngine policies, MeterRegistry registry, OperationMetrics metrics) {
        this.policies = policies;
        this.metrics = metrics;
        for (Category category : Category.values()) {
            bulkheads.put(category, new Bulkhead(() -> policies.policy(category).maxConcurrentRequests()));
            rejected.put(category, Counter.builder("filegrid.bulkhead.rejected").tag("category", category.name())
                    .description("Cereri respinse imediat de bulkhead-ul categoriei (429 CATEGORY_SATURATED)")
                    .register(registry));
        }
    }

    /**
     * The category's permit, or an {@link ApiException} 429. Use it in {@code try-with-resources} before reading the
     * body; when streaming, it is closed only after the response has finished.
     */
    public Bulkhead.Permit acquire(Category category) {
        Bulkhead.Permit permit = bulkheads.get(category).tryAcquire();
        if (permit == null) {
            rejected.get(category).increment();
            metrics.bulkheadRejected(category, policies.policy(category).primaryStorage());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CATEGORY_SATURATED",
                    "Categoria " + category + " are deja numărul maxim de cereri în curs; reîncearcă imediat.",
                    RETRY_AFTER_SECONDS, Map.of("category", category.name()));
        }
        return permit;
    }

    public Bulkhead bulkhead(Category category) {
        return bulkheads.get(category);
    }
}
