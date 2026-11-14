package ro.filegrid.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.storage.InMemoryStorageAdapter;
import ro.filegrid.storage.StorageAdapterRegistry;

/** Descărcarea și /verify peste un storage în memorie: Range, hash înainte / în timpul trimiterii, erori. */
class FileContentServiceTest {

    private final InMemoryStorageAdapter storage = new InMemoryStorageAdapter(StorageType.S3);
    private final StorageAdapterRegistry registry = new StorageAdapterRegistry(List.of(storage), List.of());
    private final DefaultFileAccess access = new DefaultFileAccess();

    private static byte[] data(int size) {
        byte[] b = new byte[size];
        new Random(5).nextBytes(b);
        return b;
    }

    private FileRecord store(byte[] content, boolean encrypted) {
        UUID id = UUID.randomUUID();
        storage.put(id.toString(), new ByteArrayInputStream(content), content.length, "application/octet-stream");
        return new FileRecord(id, encrypted ? Category.CONNECTION_DOC : Category.OUTAGE_RECORD, "avarie ăâ\"1.csv",
                "text/csv", content.length, Sha256.of(content), null, StorageType.S3, id.toString(),
                FileRecord.CONFIRMED, encrypted, Instant.now(), "teren", null);
    }

    private static void corruptOneByte(InMemoryStorageAdapter storage, FileRecord file) throws IOException {
        byte[] stored;
        try (InputStream in = storage.get(file.storageKey(), ro.filegrid.storage.ByteRange.full())) {
            stored = in.readAllBytes();
        }
        stored[stored.length / 2] ^= 0x01;
        storage.put(file.storageKey(), new ByteArrayInputStream(stored), stored.length, "x");
    }

    private MockHttpServletResponse send(FileContentService service, FileRecord file, String range) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.send(file, range, response);
        return response;
    }

    @Test
    void smallFileIsSentWholeWithHashAndRangeHeaders() throws Exception {
        byte[] content = data(200_000);
        FileRecord file = store(content, false);
        MockHttpServletResponse r = send(new FileContentService(registry, access, 1 << 20), file, null);

        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentAsByteArray()).isEqualTo(content);
        assertThat(r.getHeader("X-Content-SHA256")).isEqualTo(Sha256.hex(file.sha256()));
        assertThat(r.getHeader("Accept-Ranges")).isEqualTo("bytes");
        assertThat(r.getHeader("Content-Length")).isEqualTo("200000");
        assertThat(r.getHeader("Content-Disposition")).startsWith("attachment; filename=\"avarie ___1.csv\"")
                .contains("filename*=UTF-8''");
    }

    @Test
    void rangeGives206WithExactBytesAndOutOfRange416() throws Exception {
        byte[] content = data(300_000);
        FileRecord file = store(content, false);
        FileContentService service = new FileContentService(registry, access, 1 << 20);

        MockHttpServletResponse r = send(service, file, "bytes=65530-65545");
        assertThat(r.getStatus()).isEqualTo(206);
        assertThat(r.getHeader("Content-Range")).isEqualTo("bytes 65530-65545/300000");
        assertThat(r.getContentAsByteArray()).isEqualTo(java.util.Arrays.copyOfRange(content, 65530, 65546));
        assertThat(r.getHeader("X-Content-SHA256")).isEqualTo(Sha256.hex(file.sha256()));

        MockHttpServletResponse tail = send(service, file, "bytes=-100");
        assertThat(tail.getContentAsByteArray()).isEqualTo(java.util.Arrays.copyOfRange(content, 299_900, 300_000));

        assertThatThrownBy(() -> send(service, file, "bytes=300000-")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status().value()).isEqualTo(416));
    }

    @Test
    void corruptSmallFileGives409BeforeAnyBodyOnDownloadAndVerify() throws Exception {
        FileRecord file = store(data(100_000), false);
        corruptOneByte(storage, file);
        FileContentService service = new FileContentService(registry, access, 1 << 20);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> service.send(file, null, response)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("FILE_CORRUPT");
            assertThat(e.getMessage()).isEqualTo("fișier corupt");
            assertThat(e.details()).containsKeys("fileId", "expectedSha256", "actualSha256");
        });
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.isCommitted()).isFalse();
        assertThatThrownBy(() -> service.verify(file, false)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("FILE_CORRUPT"));
    }

    @Test
    void verifyOkReturnsHashes() {
        FileRecord file = store(data(1000), false);
        FileContentService.VerifyResult result = new FileContentService(registry, access, 1 << 20).verify(file, false);
        assertThat(result.ok()).isTrue();
        assertThat(result.actualSha256()).isEqualTo(result.expectedSha256()).isEqualTo(Sha256.hex(file.sha256()));
    }

    @Test
    void largeFileIsVerifiedWhileSendingAndCorruptionAbortsBeforeTheLastBlock() throws Exception {
        byte[] content = data(500_000);
        FileRecord file = store(content, false);
        FileContentService large = new FileContentService(registry, access, 1000); // sub prag -> „mare”
        MockHttpServletResponse ok = send(large, file, null);
        assertThat(ok.getContentAsByteArray()).isEqualTo(content);

        corruptOneByte(storage, file);
        MockHttpServletResponse partial = new MockHttpServletResponse();
        assertThatThrownBy(() -> large.send(file, null, partial)).isInstanceOf(FileContentService.TransferAborted.class);
        // Ultimul bloc a fost ținut înapoi: corpul nu s-a încheiat.
        assertThat(partial.getContentAsByteArray().length).isLessThan(content.length);
        assertThat(partial.getContentAsByteArray().length).isGreaterThan(0);
    }

    @Test
    void largeFileSmallerThanOneBlockStillGives409() throws Exception {
        FileRecord file = store(data(5000), false);
        corruptOneByte(storage, file);
        assertThatThrownBy(() -> send(new FileContentService(registry, access, 100), file, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(409));
    }

    @Test
    void missingObjectGives404AndUnavailableStorageGives503WithRetryAfter() {
        FileRecord file = store(data(1000), false);
        storage.delete(file.storageKey());
        FileContentService service = new FileContentService(registry, access, 1 << 20);
        assertThatThrownBy(() -> service.verify(file, false)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status().value()).isEqualTo(404));

        FileRecord other = store(data(1000), false);
        storage.setUp(false);
        assertThatThrownBy(() -> service.verify(other, false)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(503);
            assertThat(e.retryAfterSeconds()).isNotNull();
        });
    }

    @Test
    void encryptedFileIgnoresRangeAndUsesTheDecryptionHook() {
        FileRecord doc = store(data(1000), true);
        FileContentService service = new FileContentService(registry, access, 1 << 20);
        // Până la C10 decriptarea nu există: hook-ul refuză, fără 206 și fără octeți necriptați.
        assertThatThrownBy(() -> send(service, doc, "bytes=0-9")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status().value()).isEqualTo(501));
    }
}
