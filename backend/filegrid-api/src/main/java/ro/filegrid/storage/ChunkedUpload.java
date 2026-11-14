package ro.filegrid.storage;

import java.util.Objects;

/**
 * Datele unei sesiuni de upload în bucăți de care are nevoie un {@link ChunkedWriter}; apelantul le reconstruiește din
 * rândul {@code upload_sessions} la fiecare cerere (writer-ul nu ține stare).
 *
 * @param key       cheia obiectului final
 * @param uploadRef referința întoarsă de {@link ChunkedWriter#open} ({@code upload_sessions.storage_upload_ref})
 * @param totalSize mărimea totală, în octeți ({@code > 0})
 * @param chunkSize mărimea nominală a unei bucăți, în octeți ({@code > 0}); ultima bucată poate fi mai mică
 */
public record ChunkedUpload(String key, String uploadRef, long totalSize, int chunkSize) {

    public ChunkedUpload {
        StorageKeys.requireValid(key);
        Objects.requireNonNull(uploadRef, "uploadRef");
        if (uploadRef.isEmpty() || uploadRef.length() > 1024) {
            throw new IllegalArgumentException("uploadRef trebuie să aibă 1–1024 de caractere");
        }
        if (totalSize <= 0) {
            throw new IllegalArgumentException("totalSize trebuie să fie pozitiv");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize trebuie să fie pozitiv");
        }
    }

    /** Numărul total de bucăți: {@code ceil(totalSize / chunkSize)}. */
    public int totalChunks() {
        return Math.toIntExact((totalSize + chunkSize - 1) / chunkSize);
    }

    /** Offsetul (în octeți, de la 0) la care începe bucata {@code n} în obiectul final. */
    public long offsetOf(int n) {
        requireChunkNumber(n);
        return (long) (n - 1) * chunkSize;
    }

    /** Mărimea exactă a bucății {@code n}: {@code chunkSize}, cu excepția ultimei, care poate fi mai mică. */
    public long expectedLength(int n) {
        long offset = offsetOf(n);
        return Math.min(chunkSize, totalSize - offset);
    }

    /** @throws IllegalArgumentException dacă {@code n} nu este între 1 și {@link #totalChunks()} */
    public void requireChunkNumber(int n) {
        if (n < 1 || n > totalChunks()) {
            throw new IllegalArgumentException("Bucata " + n + " nu există (1–" + totalChunks() + ")");
        }
    }
}
