package ro.filegrid.ingest;

import java.io.IOException;
import java.io.InputStream;

/**
 * Pre-storage transformation step for categories with {@code encryption: true} in their policy:
 * streaming segmented AES-256-GCM ({@link AesGcmDocumentEncryption}).
 */
public interface DocumentEncryption {

    /** Size of the stored (encrypted) bytes for a {@code plainLength}-byte original. */
    long encryptedLength(long plainLength);

    /** Encrypted stream produced on the fly from {@code plain}; the caller reads it once and closes it. */
    InputStream encrypt(InputStream plain);

    /**
     * Plaintext stream decrypted on the fly from the stored bytes. A tampered or reordered segment, or a truncated
     * stream, is reported as {@link CorruptCiphertextException} (an {@code IOException}) on read.
     */
    InputStream decrypt(InputStream stored);

    /** Encrypted original name (ASCII text safe to store in {@code NVARCHAR(512)}). */
    String encryptName(String name);

    /** Inverse of {@link #encryptName}; {@link CorruptCiphertextException} if the value is not authentic. */
    String decryptName(String stored) throws CorruptCiphertextException;

    /** Stored bytes that fail authentication (tampered, reordered, truncated) or have an unknown format. */
    class CorruptCiphertextException extends IOException {
        public CorruptCiphertextException(String message) {
            super(message);
        }
    }
}
