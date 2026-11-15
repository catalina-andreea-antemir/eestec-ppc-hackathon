package ro.filegrid.storage;

import ro.filegrid.domain.StorageType;

/**
 * Storage error (unchecked, so it passes through lambdas and Resilience4j). Subclasses:
 * {@link StorageUnavailableException} (storage not responding: 503 + {@code Retry-After}, counted by the circuit
 * breaker) and {@link StorageObjectNotFoundException} (missing key). The message contains no secrets or personal data.
 */
public class StorageException extends RuntimeException {

    private final StorageType storage;

    public StorageException(StorageType storage, String message) {
        super(message);
        this.storage = storage;
    }

    public StorageException(StorageType storage, String message, Throwable cause) {
        super(message, cause);
        this.storage = storage;
    }

    /** The storage that produced the error (the {@code storage} metric label). */
    public StorageType storage() {
        return storage;
    }
}
