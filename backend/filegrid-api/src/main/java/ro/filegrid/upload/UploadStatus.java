package ro.filegrid.upload;

import java.time.Instant;
import java.util.UUID;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;

/** Contract schema {@code UploadSession}. */
public record UploadStatus(UUID uploadId, Category category, String fileName, StorageType storage, long totalSize,
                           int chunkSize, int totalChunks, int nextChunk, long receivedBytes, String status,
                           Instant createdAt, Instant lastActivityAt) {

    static UploadStatus of(UploadSession s, long receivedBytes) {
        return new UploadStatus(s.id(), s.category(), s.fileName(), s.storage(), s.totalSize(), s.chunkSize(),
                s.totalChunks(), s.nextChunk(), receivedBytes, s.apiStatus(), s.createdAt(), s.updatedAt());
    }
}
