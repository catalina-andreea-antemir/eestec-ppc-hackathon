package ro.filegrid.storage;

import java.time.Duration;
import java.util.Objects;

import ro.filegrid.domain.StorageType;

/**
 * Result of {@link StorageAdapter#health()}: feeds {@code filegrid_storage_up}, {@code GET /admin/health} and the
 * fallback selection.
 *
 * @param storage the checked storage
 * @param up      {@code true} if the minimal operation succeeded in time
 * @param latency duration of the check
 * @param detail  short reason when {@code down} (e.g. "Database 'FileGridStorage' is offline"); no passwords or
 *                keys; {@code null} when {@code up}
 */
public record StorageHealth(StorageType storage, boolean up, Duration latency, String detail) {

    public StorageHealth {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(latency, "latency");
    }

    public static StorageHealth up(StorageType storage, Duration latency) {
        return new StorageHealth(storage, true, latency, null);
    }

    public static StorageHealth down(StorageType storage, Duration latency, String detail) {
        return new StorageHealth(storage, false, latency, detail);
    }
}
