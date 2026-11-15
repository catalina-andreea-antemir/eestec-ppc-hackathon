package ro.filegrid.policy;

import java.util.Objects;
import java.util.OptionalInt;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;

/**
 * A category's policy (4.2), read from {@code policies/*.yml} by {@link PolicyEngine}. Values are validated in the
 * constructor, so an existing policy is always valid.
 *
 * @param category              the category
 * @param primaryStorage        primary storage
 * @param fallbackStorage       fallback storage, always different from the primary (C11)
 * @param maxSizeBytes          maximum file size in bytes (above it: 413)
 * @param encrypted             AES-GCM encryption before storage (C10)
 * @param chunkSizeBytes        chunk size for chunked uploads, between {@link #MIN_CHUNK_SIZE} and
 *                              {@link #MAX_CHUNK_SIZE}; {@code null} if the category has no chunked upload
 * @param maxConcurrentRequests concurrent requests per instance (bulkhead, C9)
 */
public record CategoryPolicy(Category category, StorageType primaryStorage, StorageType fallbackStorage,
                             long maxSizeBytes, boolean encrypted, Integer chunkSizeBytes,
                             int maxConcurrentRequests) {

    /** 5 MiB: minimum S3 / MinIO multipart part size (except the last part). */
    public static final int MIN_CHUNK_SIZE = 5 * 1024 * 1024;
    /** 64 MiB: the contract limit (a chunk fits within nginx {@code client_max_body_size 100m}). */
    public static final int MAX_CHUNK_SIZE = 64 * 1024 * 1024;
    /** Sanity cap for the bulkhead (the sum across categories stays below {@code server.tomcat.threads.max}, C9). */
    public static final int MAX_CONCURRENT_REQUESTS = 1000;

    public CategoryPolicy {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(primaryStorage, "primaryStorage");
        Objects.requireNonNull(fallbackStorage, "fallbackStorage");
        if (primaryStorage == fallbackStorage) {
            throw new IllegalArgumentException("storage-ul de rezervă trebuie să fie diferit de cel principal ("
                    + primaryStorage + ")");
        }
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("maxSize trebuie să fie pozitiv");
        }
        if (chunkSizeBytes != null && (chunkSizeBytes < MIN_CHUNK_SIZE || chunkSizeBytes > MAX_CHUNK_SIZE)) {
            throw new IllegalArgumentException("chunkSize trebuie să fie între 5 MiB și 64 MiB (acum "
                    + chunkSizeBytes + " octeți)");
        }
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > MAX_CONCURRENT_REQUESTS) {
            throw new IllegalArgumentException("maxConcurrentRequests trebuie să fie între 1 și "
                    + MAX_CONCURRENT_REQUESTS);
        }
    }

    /** The chunk size, if the category supports chunked uploads. */
    public OptionalInt chunkSize() {
        return chunkSizeBytes == null ? OptionalInt.empty() : OptionalInt.of(chunkSizeBytes);
    }
}
