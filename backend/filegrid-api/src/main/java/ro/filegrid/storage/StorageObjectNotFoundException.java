package ro.filegrid.storage;

import ro.filegrid.domain.StorageType;

/**
 * Cheia nu există în storage la {@link StorageAdapter#get}. Pentru un fișier {@code CONFIRMED} în {@code files} înseamnă
 * pierdere de date sau ștergere manuală (se loghează și se tratează ca integritate compromisă).
 */
public class StorageObjectNotFoundException extends StorageException {

    public StorageObjectNotFoundException(StorageType storage, String key) {
        super(storage, "Obiectul " + key + " nu există în storage-ul " + storage);
    }
}
