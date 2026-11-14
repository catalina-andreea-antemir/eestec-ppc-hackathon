package ro.filegrid.ingest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.policy.CategoryPolicy;
import ro.filegrid.policy.PolicyEngine;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageAdapterRegistry;

/**
 * Alege storage-ul unei scrieri din {@link PolicyEngine}: principalul dacă e sănătos, altfel rezerva. Starea
 * {@code health()} se ține minte {@value #HEALTH_TTL_MILLIS} ms, ca o cerere să nu deschidă o conexiune de verificare
 * per storage (FTP). Circuit breaker-ul și retry-ul vin la C11.
 */
@Component
public class StorageSelector {

    static final long HEALTH_TTL_MILLIS = 2000;
    private static final Logger log = LoggerFactory.getLogger(StorageSelector.class);

    private final PolicyEngine policies;
    private final StorageAdapterRegistry registry;
    private final Clock clock;
    private final Map<StorageType, Health> health = new ConcurrentHashMap<>();

    @Autowired
    public StorageSelector(PolicyEngine policies, StorageAdapterRegistry registry) {
        this(policies, registry, Clock.systemUTC());
    }

    StorageSelector(PolicyEngine policies, StorageAdapterRegistry registry, Clock clock) {
        this.policies = policies;
        this.registry = registry;
        this.clock = clock;
    }

    /**
     * Storage-urile de încercat, în ordine: cel ales de politică (principal sau, dacă e căzut, rezerva), apoi
     * rezerva, dacă e sănătoasă (pentru o scriere care eșuează deși verificarea era bună). Gol: ambele sunt căzute.
     */
    public List<StorageAdapter> candidates(Category category) {
        CategoryPolicy policy = policies.policy(category);
        List<StorageAdapter> result = new ArrayList<>(2);
        policies.chooseStorage(category, this::healthy).ifPresent(first -> {
            result.add(registry.adapter(first));
            if (first == policy.primaryStorage() && healthy(policy.fallbackStorage())) {
                result.add(registry.adapter(policy.fallbackStorage()));
            }
        });
        return result;
    }

    /** Marchează storage-ul căzut după o scriere eșuată, ca următoarele cereri să treacă direct pe rezervă. */
    public void markDown(StorageType type) {
        health.put(type, new Health(false, clock.instant().plusMillis(HEALTH_TTL_MILLIS)));
    }

    private boolean healthy(StorageType type) {
        Instant now = clock.instant();
        Health cached = health.get(type);
        if (cached != null && now.isBefore(cached.until())) {
            return cached.up();
        }
        boolean up = registry.find(type).map(adapter -> {
            try {
                return adapter.health().up();
            } catch (RuntimeException e) {
                log.warn("Verificarea storage-ului {} a eșuat: {}", type, e.getMessage());
                return false;
            }
        }).orElse(false);
        health.put(type, new Health(up, now.plus(Duration.ofMillis(HEALTH_TTL_MILLIS))));
        return up;
    }

    private record Health(boolean up, Instant until) {
    }
}
