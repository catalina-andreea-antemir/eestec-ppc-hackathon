package ro.filegrid.storage;

import ro.filegrid.domain.StorageType;

/**
 * Storage-ul nu răspunde: conexiune refuzată sau întreruptă, timeout, FileGridStorage OFFLINE (SQL Server 942 / 4060),
 * FTP căzut, MinIO oprit. Apelantul răspunde 503 cu {@code Retry-After} sau trece pe storage-ul de rezervă (C11).
 */
public class StorageUnavailableException extends StorageException {

    public StorageUnavailableException(StorageType storage, String message) {
        super(storage, message);
    }

    public StorageUnavailableException(StorageType storage, String message, Throwable cause) {
        super(storage, message, cause);
    }
}
