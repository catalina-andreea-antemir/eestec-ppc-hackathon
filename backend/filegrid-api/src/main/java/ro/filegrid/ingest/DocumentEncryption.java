package ro.filegrid.ingest;

import java.io.InputStream;

/**
 * Pasul de transformare dinaintea storage-ului pentru categoriile cu {@code encryption: true} în politică (4.1
 * „Criptare”). Implementarea AES-GCM vine la C10 și înlocuiește {@link EncryptionNotReady}; până atunci upload-ul
 * unei categorii criptate se refuză cu 503, ca datele personale să nu ajungă necriptate în storage.
 */
public interface DocumentEncryption {

    /** {@code false} cât timp nu există cheie / implementare: ingestia refuză categoriile criptate. */
    boolean ready();

    /** Mărimea octeților stocați (cifrați) pentru un original de {@code plainLength} octeți. */
    long encryptedLength(long plainLength);

    /** Fluxul cifrat, produs în streaming din {@code plain}; apelantul îl citește o singură dată și îl închide. */
    InputStream encrypt(InputStream plain);
}
