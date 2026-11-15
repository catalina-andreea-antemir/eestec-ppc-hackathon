package ro.filegrid.ingest;

import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ro.filegrid.domain.StorageType;
import ro.filegrid.policy.PolicyEngine;
import ro.filegrid.storage.InMemoryStorageAdapter;
import ro.filegrid.storage.StorageAdapterRegistry;
import ro.filegrid.storage.StorageUnavailableException;

/** In-memory storage backends, the default classpath policy and a simulated transaction. */
final class IngestTestSupport {

    final CountingAdapter s3 = new CountingAdapter(StorageType.S3);
    final CountingAdapter db = new CountingAdapter(StorageType.DB);
    final CountingAdapter ftp = new CountingAdapter(StorageType.FTP);
    final PolicyEngine policies = new PolicyEngine("classpath:/policies/", Duration.ofHours(1));
    final StorageAdapterRegistry registry = new StorageAdapterRegistry(List.of(s3, db, ftp), List.of());
    final StorageSelector selector = new StorageSelector(policies, registry);
    final ContentWriter writer = new ContentWriter(selector, registry);
    final TransactionTemplate transaction = new TransactionTemplate(mock(PlatformTransactionManager.class));

    /** Counts writes and deletes; {@code failPuts} simulates a failed write while health is "up". */
    static final class CountingAdapter extends InMemoryStorageAdapter {
        final AtomicInteger puts = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();
        volatile boolean failPuts;

        CountingAdapter(StorageType type) {
            super(type);
        }

        @Override
        public long put(String key, InputStream data, long length, String contentType) {
            if (failPuts) {
                throw new StorageUnavailableException(type(), "simulat: scriere eșuată");
            }
            puts.incrementAndGet();
            return super.put(key, data, length, contentType);
        }

        @Override
        public boolean delete(String key) {
            deletes.incrementAndGet();
            return super.delete(key);
        }
    }
}
