package ro.filegrid.storage;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Cheile obiectelor din storage. Apelantul generează cheia cu {@link #newKey()} (un UUID, planul v4 4.1: „storage_key e
 * UUID”), deci cheia nu conține nume de fișier sau date personale. Forma permisă încape în toate storage-urile:
 * {@code blob_chunks.storage_key NVARCHAR(128)}, nume de obiect S3, nume de fișier FTP (fără {@code /}).
 */
public final class StorageKeys {

    /** 1–128 caractere: litere, cifre, {@code .}, {@code _}, {@code -}; începe cu literă sau cifră. */
    public static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private StorageKeys() {
    }

    /** O cheie nouă, aleatorie (UUID în forma canonică, 36 de caractere). */
    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** @throws IllegalArgumentException dacă cheia lipsește sau nu respectă {@link #PATTERN} */
    public static String requireValid(String key) {
        if (key == null || !PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Cheie de storage invalidă");
        }
        return key;
    }
}
