package ro.filegrid.storage;

import ro.filegrid.domain.StorageType;

/**
 * The key does not exist in storage on {@link StorageAdapter#get}. For a {@code CONFIRMED} file in {@code files} this
 * means data loss or manual deletion (logged and treated as compromised integrity).
 */
public class StorageObjectNotFoundException extends StorageException {

    public StorageObjectNotFoundException(StorageType storage, String key) {
        super(storage, "Obiectul " + key + " nu există în storage-ul " + storage);
    }
}
