package ro.filegrid.storage;

import java.util.Objects;

/**
 * The chunked upload session data a {@link ChunkedWriter} needs; the caller rebuilds it from the
 * {@code upload_sessions} row on every request (the writer is stateless).
 *
 * @param key       key of the final object
 * @param uploadRef reference returned by {@link ChunkedWriter#open} ({@code upload_sessions.storage_upload_ref})
 * @param totalSize total size in bytes ({@code > 0})
 * @param chunkSize nominal chunk size in bytes ({@code > 0}); the last chunk may be smaller
 */
public record ChunkedUpload(String key, String uploadRef, long totalSize, int chunkSize) {

    public ChunkedUpload {
        StorageKeys.requireValid(key);
        Objects.requireNonNull(uploadRef, "uploadRef");
        if (uploadRef.isEmpty() || uploadRef.length() > 1024) {
            throw new IllegalArgumentException("uploadRef trebuie să aibă 1–1024 de caractere");
        }
        if (totalSize <= 0) {
            throw new IllegalArgumentException("totalSize trebuie să fie pozitiv");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize trebuie să fie pozitiv");
        }
    }

    /** Total number of chunks: {@code ceil(totalSize / chunkSize)}. */
    public int totalChunks() {
        return Math.toIntExact((totalSize + chunkSize - 1) / chunkSize);
    }

    /** Byte offset (zero-based) where chunk {@code n} starts in the final object. */
    public long offsetOf(int n) {
        requireChunkNumber(n);
        return (long) (n - 1) * chunkSize;
    }

    /** Exact size of chunk {@code n}: {@code chunkSize}, except the last one, which may be smaller. */
    public long expectedLength(int n) {
        long offset = offsetOf(n);
        return Math.min(chunkSize, totalSize - offset);
    }

    /** @throws IllegalArgumentException if {@code n} is not between 1 and {@link #totalChunks()} */
    public void requireChunkNumber(int n) {
        if (n < 1 || n > totalChunks()) {
            throw new IllegalArgumentException("Bucata " + n + " nu există (1–" + totalChunks() + ")");
        }
    }
}
