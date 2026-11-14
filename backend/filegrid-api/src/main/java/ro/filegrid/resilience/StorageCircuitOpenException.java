package ro.filegrid.resilience;

import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.StorageUnavailableException;

/** Circuitul storage-ului e deschis: operația eșuează imediat, fără apel către storage și fără retry. */
public class StorageCircuitOpenException extends StorageUnavailableException {

    public StorageCircuitOpenException(StorageType storage, Throwable cause) {
        super(storage, "Circuit breaker deschis pentru storage-ul " + storage, cause);
    }
}
