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

import io.micrometer.core.instrument.MeterRegistry;
import ro.filegrid.observability.OperationMetrics;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageAdapterRegistry;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageUnavailableException;

/**
 * Writes a request's content to the storage chosen by the policy (C7): new UUID key, streaming SHA-256 over the
 * stored bytes (and over the original when it goes through encryption), never holding the file in memory. If the
 * write to the primary fails, the source is reopened and the fallback is tried.
 */
@Component
public class ContentWriter {

    private static final Logger log = LoggerFactory.getLogger(ContentWriter.class);

    private final StorageSelector selector;
    private final StorageAdapterRegistry registry;

    private final OperationMetrics metrics;

    public ContentWriter(StorageSelector selector, StorageAdapterRegistry registry) {
        this(selector, registry, OperationMetrics.noop());
    }

    public ContentWriter(StorageSelector selector, StorageAdapterRegistry registry, MeterRegistry meters) {
        this(selector, registry, new OperationMetrics(meters, null));
    }

    @Autowired
    public ContentWriter(StorageSelector selector, StorageAdapterRegistry registry, OperationMetrics metrics) {
        this.selector = selector;
        this.registry = registry;
        this.metrics = metrics;
    }

    /**
     * @param encryption the transformation step (C10), or {@code null} for unencrypted categories
     * @return where it was written and the hashes; throws 503 {@code STORAGE_UNAVAILABLE} if no storage confirmed
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
                metrics.run(category, adapter.type(), OperationMetrics.UPLOAD, length,
                        () -> adapter.put(key, stored, length, contentType));
                byte[] storedHash = stored.getMessageDigest().digest();
                byte[] plainHash = encryption == null ? null : plain.getMessageDigest().digest();
                return new Stored(adapter.type(), key, storedHash, plainHash, length);
            } catch (StorageException e) {
                if (e instanceof StorageUnavailableException) {
                    selector.markDown(adapter.type());
                }
                log.warn("Writing {} to storage {} failed: {}", category, adapter.type(), e.getMessage());
            } catch (IOException e) {
                throw new UncheckedIOException("Nu pot citi conținutul primit", e);
            }
        }
        throw IngestErrors.storageUnavailable("Niciun storage pentru " + category
                + " (principal sau de rezervă) nu este disponibil; reîncearcă.");
    }

    /** Compensation: deletes the written object if the metadata could not be saved. Never throws. */
    public void deleteQuietly(Category category, StorageType storage, String key) {
        try {
            metrics.run(category, storage, OperationMetrics.DELETE, null, () -> registry.adapter(storage).delete(key));
        } catch (RuntimeException e) {
            log.error("Compensation failed: object {} in {} was left without metadata: {}", key, storage,
                    e.getMessage());
        }
    }

    /** Content source; can be opened more than once (multipart temp file or in-memory bytes). */
    @FunctionalInterface
    public interface Source {
        InputStream open() throws IOException;
    }

    /** Result of a write confirmed by storage. */
    public record Stored(StorageType storage, String key, byte[] sha256, byte[] sha256Plain, long storedLength) {
    }
}
