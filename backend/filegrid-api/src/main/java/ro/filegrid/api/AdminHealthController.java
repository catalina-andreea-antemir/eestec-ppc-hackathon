package ro.filegrid.api;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;

import ro.filegrid.domain.StorageType;
import ro.filegrid.resilience.StorageGuard;
import ro.filegrid.storage.StorageAdapterRegistry;
import ro.filegrid.storage.StorageHealth;

/**
 * {@code GET /api/v1/admin/health} (ADMIN, contract {@code getAdminHealth}): state of every storage backend (cached
 * health + circuit breaker) and of FileGridMeta, as seen by the answering instance. Returns 200 even when a storage is DOWN.
 */
@RestController
public class AdminHealthController {

    private final StorageGuard guard;
    private final StorageAdapterRegistry registry;
    private final HealthController meta;
    private final String instance;
    private final Clock clock;

    @Autowired
    public AdminHealthController(StorageGuard guard, StorageAdapterRegistry registry, HealthController meta,
                                 @Value("${filegrid.instance:${HOSTNAME:filegrid-api}}") String instance) {
        this(guard, registry, meta, instance, Clock.systemUTC());
    }

    AdminHealthController(StorageGuard guard, StorageAdapterRegistry registry, HealthController meta,
                          String instance, Clock clock) {
        this.guard = guard;
        this.registry = registry;
        this.meta = meta;
        this.instance = instance;
        this.clock = clock;
    }

    @GetMapping("/api/v1/admin/health")
    public AdminHealth health() {
        boolean metaUp = meta.metaDatabaseUp();
        List<StorageState> storages = new ArrayList<>();
        boolean degraded = false;
        for (StorageType type : StorageType.values()) {
            StorageHealth h = guard.health(type);
            boolean present = registry.find(type).isPresent();
            boolean up = h.up() && present;
            degraded |= !up;
            storages.add(new StorageState(type.name(), up ? "UP" : "DOWN", guard.circuitState(type),
                    up ? null : present ? h.detail() : "fără adaptor"));
        }
        String status = !metaUp ? "DOWN" : degraded ? "DEGRADED" : "UP";
        return new AdminHealth(status, instance, clock.instant(), new MetaState(metaUp ? "UP" : "DOWN"), storages);
    }

    public record AdminHealth(String status, String instance, Instant checkedAt, MetaState metadataDb,
                              List<StorageState> storages) {
    }

    public record MetaState(String status) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StorageState(String storage, String status, String circuitBreaker, String detail) {
    }
}
