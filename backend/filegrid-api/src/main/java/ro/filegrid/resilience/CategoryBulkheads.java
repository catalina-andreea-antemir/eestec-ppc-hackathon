package ro.filegrid.resilience;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.policy.PolicyEngine;

/**
 * Izolare per categorie (C9, planul 4.1): câte un {@link Bulkhead} pe categorie, dimensionat din
 * {@code maxConcurrentRequests} al politicii (per instanță, recitit fără restart). Saturația dă 429
 * {@code CATEGORY_SATURATED} cu {@code Retry-After}, doar pentru categoria saturată, și incrementează
 * {@code filegrid_bulkhead_rejected_total{category}}.
 */
@Component
public class CategoryBulkheads {

    /** Secunde până la reîncercare; permisele se eliberează în ordinul secundelor. */
    static final int RETRY_AFTER_SECONDS = 1;

    private final Map<Category, Bulkhead> bulkheads = new EnumMap<>(Category.class);
    private final Map<Category, Counter> rejected = new EnumMap<>(Category.class);

    public CategoryBulkheads(PolicyEngine policies, MeterRegistry registry) {
        for (Category category : Category.values()) {
            bulkheads.put(category, new Bulkhead(() -> policies.policy(category).maxConcurrentRequests()));
            rejected.put(category, Counter.builder("filegrid.bulkhead.rejected").tag("category", category.name())
                    .description("Cereri respinse imediat de bulkhead-ul categoriei (429 CATEGORY_SATURATED)")
                    .register(registry));
        }
    }

    /**
     * Permisul categoriei sau {@link ApiException} 429. Se folosește în {@code try-with-resources}, înainte de
     * citirea corpului; la streaming se închide abia după ce răspunsul s-a terminat.
     */
    public Bulkhead.Permit acquire(Category category) {
        Bulkhead.Permit permit = bulkheads.get(category).tryAcquire();
        if (permit == null) {
            rejected.get(category).increment();
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
