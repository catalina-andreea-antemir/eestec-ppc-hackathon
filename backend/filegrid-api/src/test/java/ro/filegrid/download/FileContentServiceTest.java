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

/** Download and /verify over an in-memory storage: Range, hash before / while sending, errors. */
class FileContentServiceTest {

    private final InMemoryStorageAdapter storage = new InMemoryStorageAdapter(StorageType.S3);
    private final StorageAdapterRegistry registry = new StorageAdapterRegistry(List.of(storage), List.of());
    private final RoleBasedFileAccess access = new RoleBasedFileAccess(
            org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
            new ro.filegrid.ingest.AesGcmDocumentEncryption(ro.filegrid.TestSecrets.DOC_KEY_BYTES),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

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
        FileContentService large = new FileContentService(registry, access, 1000); // below the threshold -> "large"
        MockHttpServletResponse ok = send(large, file, null);
        assertThat(ok.getContentAsByteArray()).isEqualTo(content);

        corruptOneByte(storage, file);
        MockHttpServletResponse partial = new MockHttpServletResponse();
        assertThatThrownBy(() -> large.send(file, null, partial)).isInstanceOf(FileContentService.TransferAborted.class);
        // The last block was held back: the body never completed.
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


    private static final ro.filegrid.ingest.AesGcmDocumentEncryption ENC =
            new ro.filegrid.ingest.AesGcmDocumentEncryption(ro.filegrid.TestSecrets.DOC_KEY_BYTES);

    /** Encrypted document: stored bytes are ciphertext, sha256 over them, sha256_plain over the original, size = original. */
    private FileRecord storeEncrypted(byte[] plain) throws IOException {
        byte[] cipher = ENC.encrypt(new ByteArrayInputStream(plain)).readAllBytes();
        UUID id = UUID.randomUUID();
        storage.put(id.toString(), new ByteArrayInputStream(cipher), cipher.length, "application/octet-stream");
        return new FileRecord(id, Category.CONNECTION_DOC, "cerere.pdf", "application/pdf", plain.length,
                Sha256.of(cipher), Sha256.of(plain), StorageType.S3, id.toString(), FileRecord.CONFIRMED, true,
                Instant.now(), "racordari", null);
    }

    private FileRecord tampered(FileRecord doc) throws IOException {
        corruptOneByte(storage, doc);
        byte[] now;
        try (InputStream in = storage.get(doc.storageKey(), ro.filegrid.storage.ByteRange.full())) {
            now = in.readAllBytes();
        }
        // sha256 over the stored bytes "matches" again: only GCM can still catch the tampering.
        return new FileRecord(doc.id(), doc.category(), doc.originalName(), doc.contentType(), doc.size(),
                Sha256.of(now), doc.sha256Plain(), doc.storage(), doc.storageKey(), doc.status(), true,
                doc.createdAt(), doc.createdBy(), null);
    }

    @Test
    void encryptedFileIsDecryptedWithoutRangeAndShowsPlainHash() throws Exception {
        byte[] plain = data(200_000);
        FileRecord doc = storeEncrypted(plain);
        FileContentService service = new FileContentService(registry, access, 1 << 20);

        MockHttpServletResponse r = send(service, doc, "bytes=0-9");

        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("Accept-Ranges")).isNull();
        assertThat(r.getHeader("Content-Range")).isNull();
        assertThat(r.getHeader("X-Content-SHA256")).isEqualTo(Sha256.hex(Sha256.of(plain)));
        assertThat(r.getContentAsByteArray()).isEqualTo(plain);
    }

    @Test
    void verifyChecksStoredHashAndDeepAlsoChecksPlainHash() throws Exception {
        FileRecord doc = storeEncrypted(data(150_000));
        FileContentService service = new FileContentService(registry, access, 1 << 20);

        FileContentService.VerifyResult shallow = service.verify(doc, false);
        FileContentService.VerifyResult deep = service.verify(doc, true);

        assertThat(shallow.deep()).isFalse();
        assertThat(shallow.expectedSha256Plain()).isNull();
        assertThat(deep.ok()).isTrue();
        assertThat(deep.deep()).isTrue();
        assertThat(deep.actualSha256Plain()).isEqualTo(Sha256.hex(doc.sha256Plain()));
    }

    @Test
    void alteredEncryptedSegmentGives409EverywhereEvenWhenStoredHashMatches() throws Exception {
        FileContentService service = new FileContentService(registry, access, 1 << 20);
        FileRecord plainHashCaught = storeEncrypted(data(150_000));
        corruptOneByte(storage, plainHashCaught);
        assertThatThrownBy(() -> service.verify(plainHashCaught, false)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("FILE_CORRUPT"));

        FileRecord gcmCaught = tampered(storeEncrypted(data(150_000)));
        assertThat(service.verify(gcmCaught, false).ok()).isTrue(); // hash over the stored bytes: sees nothing
        assertThatThrownBy(() -> service.verify(gcmCaught, true)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("FILE_CORRUPT");
        });
        // Tampering in the second segment: the first block already left, so the connection is cut (as for large files).
        assertThatThrownBy(() -> send(service, tampered(storeEncrypted(data(150_000))), null))
                .isInstanceOf(FileContentService.TransferAborted.class);
        // Tampering in the first segment: nothing has left yet, so a clean 409.
        FileRecord early = storeEncrypted(data(150_000));
        byte[] raw;
        try (InputStream in = storage.get(early.storageKey(), ro.filegrid.storage.ByteRange.full())) {
            raw = in.readAllBytes();
        }
        raw[30] ^= 1;
        storage.put(early.storageKey(), new ByteArrayInputStream(raw), raw.length, "x");
        FileRecord earlyRecord = new FileRecord(early.id(), early.category(), early.originalName(), early.contentType(),
                early.size(), Sha256.of(raw), early.sha256Plain(), early.storage(), early.storageKey(), early.status(),
                true, early.createdAt(), early.createdBy(), null);
        assertThatThrownBy(() -> send(service, earlyRecord, null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("FILE_CORRUPT"));
    }
}
