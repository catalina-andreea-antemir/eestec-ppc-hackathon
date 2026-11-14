package ro.filegrid.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageAdapterRegistry;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageUnavailableException;

/**
 * Scrie conținutul unei cereri în storage-ul ales de politică (C7): cheie UUID nouă, SHA-256 în streaming pe
 * octeții stocați (și pe original, dacă trece prin pasul de criptare), fără să țină fișierul în memorie. Dacă
 * scrierea pe principal eșuează, sursa se redeschide și se încearcă rezerva.
 */
@Component
public class ContentWriter {

    private static final Logger log = LoggerFactory.getLogger(ContentWriter.class);

    private final StorageSelector selector;
    private final StorageAdapterRegistry registry;

    private final MeterRegistry meters;

    public ContentWriter(StorageSelector selector, StorageAdapterRegistry registry) {
        this(selector, registry, new SimpleMeterRegistry());
    }

    @Autowired
    public ContentWriter(StorageSelector selector, StorageAdapterRegistry registry, MeterRegistry meters) {
        this.selector = selector;
        this.registry = registry;
        this.meters = meters;
    }

    /**
     * @param encryption pasul de transformare (C10) sau {@code null} pentru categoriile necriptate
     * @return unde s-a scris și hash-urile; aruncă 503 {@code STORAGE_UNAVAILABLE} dacă niciun storage nu a confirmat
     */
    public Stored write(Category category, long plainLength, String contentType, Source source,
                        DocumentEncryption encryption) {
        List<StorageAdapter> candidates = selector.candidates(category);
        String key = StorageKeys.newKey();
        for (StorageAdapter adapter : candidates) {
            try (InputStream original = source.open()) {
                DigestInputStream plain = Sha256.digesting(original);
                InputStream content = encryption == null ? plain : encryption.encrypt(plain);
                long length = encryption == null ? plainLength : encryption.encryptedLength(plainLength);
                DigestInputStream stored = Sha256.digesting(content);
                adapter.put(key, stored, length, contentType);
                byte[] storedHash = stored.getMessageDigest().digest();
                byte[] plainHash = encryption == null ? null : plain.getMessageDigest().digest();
                return new Stored(adapter.type(), key, storedHash, plainHash, length);
            } catch (StorageException e) {
                if (e instanceof StorageUnavailableException) {
                    selector.markDown(adapter.type());
                }
                Counter.builder("filegrid.transfers.failed").tag("category", category.name())
                        .tag("storage", adapter.type().name()).register(meters).increment();
                log.warn("Scrierea {} în storage-ul {} a eșuat: {}", category, adapter.type(), e.getMessage());
            } catch (IOException e) {
                throw new UncheckedIOException("Nu pot citi conținutul primit", e);
            }
        }
        throw IngestErrors.storageUnavailable("Niciun storage pentru " + category
                + " (principal sau de rezervă) nu este disponibil; reîncearcă.");
    }

    /** Compensare: șterge obiectul scris dacă metadatele nu s-au putut salva. Nu aruncă. */
    public void deleteQuietly(StorageType storage, String key) {
        try {
            registry.adapter(storage).delete(key);
        } catch (RuntimeException e) {
            log.error("Compensare eșuată: obiectul {} din {} a rămas fără metadate: {}", key, storage,
                    e.getMessage());
        }
    }

    /** Sursa conținutului; se poate deschide de mai multe ori (fișier temporar multipart sau octeți în memorie). */
    @FunctionalInterface
    public interface Source {
        InputStream open() throws IOException;
    }

    /** Rezultatul unei scrieri confirmate de storage. */
    public record Stored(StorageType storage, String key, byte[] sha256, byte[] sha256Plain, long storedLength) {
    }
}
