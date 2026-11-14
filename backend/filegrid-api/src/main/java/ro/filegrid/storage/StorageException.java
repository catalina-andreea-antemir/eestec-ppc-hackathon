package ro.filegrid.storage;

import ro.filegrid.domain.StorageType;

/**
 * Eroare de storage (necontrolată, ca să treacă prin lambda-uri și prin Resilience4j). Subclase:
 * {@link StorageUnavailableException} (storage-ul nu răspunde: 503 + {@code Retry-After}, se numără în circuit
 * breaker) și {@link StorageObjectNotFoundException} (cheie inexistentă). Mesajul nu conține secrete sau date personale.
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

    /** Storage-ul care a produs eroarea (eticheta {@code storage} din metrici). */
    public StorageType storage() {
        return storage;
    }
}
