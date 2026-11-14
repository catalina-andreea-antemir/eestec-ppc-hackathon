package ro.filegrid.upload;

import java.time.Instant;
import java.util.UUID;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.ChunkedUpload;

/** Un rând din {@code upload_sessions} (starea unui upload în bucăți, doar în FileGridMeta). */
public record UploadSession(UUID id, Category category, StorageType storage, String uploadRef, String fileName,
                            String contentType, int chunkSize, long totalSize, String status, int nextChunk,
                            Instant createdAt, Instant updatedAt, Instant storageUnavailableSince, String createdBy) {

    public static final String PENDING = "PENDING";
    public static final String COMPLETED = "COMPLETED";
    public static final String ABORTED = "ABORTED";
    public static final String FAILED = "FAILED";

    /** Cheia obiectului final este chiar {@code uploadId} (UUID), la fel ca {@code files.id}. */
    public ChunkedUpload toChunkedUpload() {
        return new ChunkedUpload(id.toString(), uploadRef, totalSize, chunkSize);
    }

    public int totalChunks() {
        return toChunkedUpload().totalChunks();
    }

    public boolean pending() {
        return PENDING.equals(status);
    }

    /** Starea din contract: IN_PROGRESS, COMPLETED, ABORTED, ABANDONED. */
    public String apiStatus() {
        return switch (status) {
            case PENDING -> "IN_PROGRESS";
            case FAILED -> "ABANDONED";
            default -> status;
        };
    }
}
