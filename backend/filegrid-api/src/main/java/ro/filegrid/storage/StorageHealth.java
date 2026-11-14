package ro.filegrid.storage;

import java.time.Duration;
import java.util.Objects;

import ro.filegrid.domain.StorageType;

/**
 * Rezultatul {@link StorageAdapter#health()}: alimentează {@code filegrid_storage_up}, {@code GET /admin/health} și
 * alegerea storage-ului de rezervă.
 *
 * @param storage storage-ul verificat
 * @param up      {@code true} dacă operația minimă a reușit în timp
 * @param latency durata verificării
 * @param detail  scurt motiv la {@code down} (de exemplu „Database 'FileGridStorage' is offline”); fără parole sau
 *                chei; {@code null} la {@code up}
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
