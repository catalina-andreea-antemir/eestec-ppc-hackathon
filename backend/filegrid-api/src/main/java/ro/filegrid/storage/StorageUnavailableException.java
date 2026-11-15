package ro.filegrid.storage;

import ro.filegrid.domain.StorageType;

/**
 * The storage does not respond: connection refused or dropped, timeout, FileGridStorage OFFLINE (SQL Server 942 / 4060),
 * FTP down, MinIO stopped. The caller answers 503 with {@code Retry-After} or switches to the fallback storage (C11).
 */
public class StorageUnavailableException extends StorageException {

    public StorageUnavailableException(StorageType storage, String message) {
        super(storage, message);
    }

    public StorageUnavailableException(StorageType storage, String message, Throwable cause) {
        super(storage, message, cause);
    }
}
