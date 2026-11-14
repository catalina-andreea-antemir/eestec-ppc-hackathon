package ro.filegrid.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.storage.ByteRange;

class FileIngestionServiceTest {

    private final IngestTestSupport env = new IngestTestSupport();
    private final FileMetadataRepository files = mock(FileMetadataRepository.class);
    private final DocumentEncryption encryption = new EncryptionNotReady();
    private final FileIngestionService service = new FileIngestionService(env.policies, env.writer, files,
            env.transaction, encryption, Clock.systemUTC());
    private final byte[] content = random(300_000);

    @Test
    void outageGoesToPrimaryWithStreamingHashAndMetadataAfterStorage() throws Exception {
        FileRecord stored = service.upload(upload(Category.OUTAGE_RECORD, content.length));

        assertThat(stored.storage()).isEqualTo(StorageType.S3);
        assertThat(stored.status()).isEqualTo("CONFIRMED");
        assertThat(stored.sha256()).isEqualTo(Sha256.of(content));
        assertThat(stored.sha256Plain()).isNull();
        assertThat(stored.outage().zone()).isEqualTo("Banat");
        try (InputStream back = env.s3.get(stored.storageKey(), ByteRange.full())) {
            assertThat(back.readAllBytes()).isEqualTo(content);
        }
        verify(files).insert(stored);
    }

    @Test
    void primaryDownGoesToFallback() {
        env.s3.setUp(false);

        assertThat(service.upload(upload(Category.OUTAGE_RECORD, content.length)).storage()).isEqualTo(StorageType.DB);
        assertThat(env.s3.puts).hasValue(0);
    }

    @Test
    void failedPrimaryWriteRetriesOnFallback() {
        env.s3.failPuts = true;

        FileRecord stored = service.upload(upload(Category.OUTAGE_RECORD, content.length));

        assertThat(stored.storage()).isEqualTo(StorageType.DB);
        assertThat(stored.sha256()).isEqualTo(Sha256.of(content));
    }

    @Test
    void bothStoragesDownGive503WithoutMetadata() {
        env.s3.setUp(false);
        env.db.setUp(false);

        assertThatThrownBy(() -> service.upload(upload(Category.OUTAGE_RECORD, content.length)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.code()).isEqualTo("STORAGE_UNAVAILABLE");
                    assertThat(e.retryAfterSeconds()).isPositive();
                });
        verify(files, never()).insert(any());
    }

    @Test
    void metadataFailureDeletesTheStoredObject() {
        doThrow(new DataAccessResourceFailureException("simulat")).when(files).insert(any());

        assertThatThrownBy(() -> service.upload(upload(Category.OUTAGE_RECORD, content.length)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
        assertThat(env.s3.puts).hasValue(1);
        assertThat(env.s3.deletes).hasValue(1);
    }

    @Test
    void sizeAbovePolicyIs413BeforeAnyWrite() {
        long max = env.policies.policy(Category.OUTAGE_RECORD).maxSizeBytes();

        assertThatThrownBy(() -> service.upload(upload(Category.OUTAGE_RECORD, max + 1)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(413);
                    assertThat(e.details()).containsEntry("maxBytes", max);
                });
        assertThat(env.s3.puts).hasValue(0);
    }

    @Test
    void encryptedCategoryIsRefusedUntilEncryptionExists() {
        assertThatThrownBy(() -> service.upload(upload(Category.CONNECTION_DOC, content.length)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.getMessage()).contains("Criptarea");
                });
        assertThat(env.db.puts.get() + env.s3.puts.get()).isZero();
        verify(files, never()).insert(any());
    }

    private FileIngestionService.Upload upload(Category category, long size) {
        FileRecord.OutageInfo outage = category == Category.OUTAGE_RECORD
                ? new FileRecord.OutageInfo("Banat", "ST-1", Instant.parse("2026-11-14T09:32:00Z")) : null;
        return new FileIngestionService.Upload(category, "avarie.csv", "text/csv", size,
                () -> new ByteArrayInputStream(content), outage, "teren");
    }

    static byte[] random(int size) {
        byte[] data = new byte[size];
        new Random(7).nextBytes(data);
        return data;
    }
}
