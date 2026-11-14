package ro.filegrid.ingest;

import java.io.IOException;
import java.io.InputStream;

/**
 * Pasul de transformare dinaintea storage-ului pentru categoriile cu {@code encryption: true} în politică (4.1
 * „Criptare”): AES-256-GCM pe segmente, în streaming ({@link AesGcmDocumentEncryption}).
 */
public interface DocumentEncryption {

    /** Mărimea octeților stocați (cifrați) pentru un original de {@code plainLength} octeți. */
    long encryptedLength(long plainLength);

    /** Fluxul cifrat, produs în streaming din {@code plain}; apelantul îl citește o singură dată și îl închide. */
    InputStream encrypt(InputStream plain);

    /**
     * Fluxul în clar, decriptat în streaming din octeții stocați. Un segment alterat, reordonat sau un flux trunchiat
     * se semnalează cu {@link CorruptCiphertextException} (un {@code IOException}) la citire.
     */
    InputStream decrypt(InputStream stored);

    /** Numele original, criptat (text ASCII sigur de pus în {@code NVARCHAR(512)}). */
    String encryptName(String name);

    /** Inversul lui {@link #encryptName}; {@link CorruptCiphertextException} dacă valoarea nu e autentică. */
    String decryptName(String stored) throws CorruptCiphertextException;

    /** Octeți stocați care nu se autentifică (alterați, reordonați, trunchiați) sau format necunoscut. */
    class CorruptCiphertextException extends IOException {
        public CorruptCiphertextException(String message) {
            super(message);
        }
    }
}
