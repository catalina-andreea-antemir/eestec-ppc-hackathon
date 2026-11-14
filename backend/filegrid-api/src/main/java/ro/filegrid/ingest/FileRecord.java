package ro.filegrid.ingest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;

/** Un rând din {@code files} (FileGridMeta), plus detaliile de avarie când există. */
public record FileRecord(UUID id, Category category, String originalName, String contentType, long size,
                         byte[] sha256, byte[] sha256Plain, StorageType storage, String storageKey, String status,
                         boolean encrypted, Instant createdAt, String createdBy, OutageInfo outage) {

    public static final String CONFIRMED = "CONFIRMED";

    public FileRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(storageKey, "storageKey");
    }

    /** Zona, stația și momentul avariei ({@code outage_records}). */
    public record OutageInfo(String zone, String stationId, Instant occurredAt) {
        public OutageInfo {
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
