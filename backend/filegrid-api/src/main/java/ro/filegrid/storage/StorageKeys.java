package ro.filegrid.storage;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Storage object keys. The caller generates the key with {@link #newKey()} (a UUID), so the key never contains a
 * file name or personal data. The allowed shape fits every storage: {@code blob_chunks.storage_key NVARCHAR(128)},
 * S3 object names, FTP file names (no {@code /}).
 */
public final class StorageKeys {

    /** 1-128 characters: letters, digits, {@code .}, {@code _}, {@code -}; starts with a letter or digit. */
    public static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private StorageKeys() {
    }

    /** A new random key (canonical UUID, 36 characters). */
    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** @throws IllegalArgumentException if the key is missing or does not match {@link #PATTERN} */
    public static String requireValid(String key) {
        if (key == null || !PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Cheie de storage invalidă");
        }
        return key;
    }
}
