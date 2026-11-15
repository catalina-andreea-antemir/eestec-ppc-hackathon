package ro.filegrid.api;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.integrity.Sha256;

/** Contract schema {@code FileMetadata}. {@code sha256Plain} is always present (null for unencrypted files). */
public record FileMetadata(UUID id, Category category, String originalName, String contentType, long size,
                           String sha256, String sha256Plain, StorageType storage, String status, boolean encrypted,
                           Instant createdAt, String createdBy,
                           @JsonInclude(JsonInclude.Include.NON_NULL) OutageDetails outage) {

    public static FileMetadata of(FileRecord file) {
        FileRecord.OutageInfo o = file.outage();
        return new FileMetadata(file.id(), file.category(), file.originalName(), file.contentType(), file.size(),
                Sha256.hex(file.sha256()), Sha256.hex(file.sha256Plain()), file.storage(), file.status(),
                file.encrypted(), file.createdAt(), file.createdBy(),
                o == null ? null : new OutageDetails(o.zone(), o.stationId(), o.occurredAt()));
    }

    /** Schema {@code OutageDetails}: the {@code outage_records} row. */
    public record OutageDetails(String zone, String stationId, Instant occurredAt) {
    }
}
