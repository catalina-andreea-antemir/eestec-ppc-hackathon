package ro.filegrid.policy;

import java.util.Objects;
import java.util.OptionalInt;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;

/**
 * Politica unei categorii (planul v4 4.2), citită din {@code policies/*.yml} de {@link PolicyEngine}. Valorile se
 * validează la construcție, deci o politică existentă este mereu validă.
 *
 * @param category              categoria
 * @param primaryStorage        storage-ul principal
 * @param fallbackStorage       storage-ul de rezervă, mereu diferit de cel principal (C11)
 * @param maxSizeBytes          mărimea maximă a unui fișier, în octeți (peste ea: 413)
 * @param encrypted             criptare AES-GCM înainte de storage (C10)
 * @param chunkSizeBytes        mărimea bucății pentru upload în bucăți, între {@link #MIN_CHUNK_SIZE} și
 *                              {@link #MAX_CHUNK_SIZE}; {@code null} dacă categoria nu are upload în bucăți
 * @param maxConcurrentRequests cereri paralele per instanță (bulkhead, C9)
 */
public record CategoryPolicy(Category category, StorageType primaryStorage, StorageType fallbackStorage,
                             long maxSizeBytes, boolean encrypted, Integer chunkSizeBytes,
                             int maxConcurrentRequests) {

    /** 5 MiB: partea minimă la multipart S3 / MinIO (în afară de ultima). */
    public static final int MIN_CHUNK_SIZE = 5 * 1024 * 1024;
    /** 64 MiB: limita din contract (o bucată încape în {@code client_max_body_size 100m}). */
    public static final int MAX_CHUNK_SIZE = 64 * 1024 * 1024;
    /** Plafon de bun-simț pentru bulkhead (suma pe categorii rămâne sub {@code server.tomcat.threads.max}, C9). */
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

    /** Mărimea bucății, dacă categoria are upload în bucăți. */
    public OptionalInt chunkSize() {
        return chunkSizeBytes == null ? OptionalInt.empty() : OptionalInt.of(chunkSizeBytes);
    }
}
