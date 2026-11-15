package ro.filegrid.ingest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;

/** A {@code files} row (FileGridMeta), plus outage details when present. */
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

    /** Same record with another name (encrypted name from the database <-> plaintext name for the response). */
    public FileRecord withOriginalName(String name) {
        return new FileRecord(id, category, name, contentType, size, sha256, sha256Plain, storage, storageKey, status,
                encrypted, createdAt, createdBy, outage);
    }

    /** Outage zone, station and timestamp ({@code outage_records}). */
    public record OutageInfo(String zone, String stationId, Instant occurredAt) {
        public OutageInfo {
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
