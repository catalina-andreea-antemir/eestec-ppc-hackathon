package ro.filegrid.resilience;

import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.StorageUnavailableException;

/** The storage circuit is open: the operation fails immediately, with no storage call and no retry. */
public class StorageCircuitOpenException extends StorageUnavailableException {

    public StorageCircuitOpenException(StorageType storage, Throwable cause) {
        super(storage, "Circuit breaker deschis pentru storage-ul " + storage, cause);
    }
}
