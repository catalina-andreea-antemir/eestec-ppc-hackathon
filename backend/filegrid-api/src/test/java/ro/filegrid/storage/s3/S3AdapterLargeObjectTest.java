package ro.filegrid.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.ChunkReceipt;
import ro.filegrid.storage.ChunkedUpload;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageUnavailableException;

/**
 * Criteriile de acceptanță C5 pe MinIO-ul real (se sare fără MinIO): fișier de 200 MB urcat și descărcat în streaming cu
 * hash identic (rulat și cu {@code -DargLine=-Xmx256m}), descărcare parțială exactă, upload în bucăți de 16 MiB cu o
 * bucată retrimisă și una întreruptă, abort fără upload-uri multipart neterminate. Datele se generează din
 * {@link GeneratedData}, deci nimic de dimensiunea fișierului nu stă în memorie.
 */
class S3AdapterLargeObjectTest {

    private static final Logger log = LoggerFactory.getLogger(S3AdapterLargeObjectTest.class);
    private static final int MIB = 1024 * 1024;

    private static S3TestSupport.TrackingS3Adapter s3;

    @BeforeAll
    static void connect() {
        s3 = S3TestSupport.connectOrSkip();
    }

    @AfterAll
    static void cleanup() {
        if (s3 == null) {
            return;
        }
        try {
            for (String key : s3.keys) {
                assertThat(s3.pendingUploads(key)).as("upload-uri multipart neterminate pentru " + key).isEmpty();
            }
        } finally {
            s3.cleanupAndClose();
        }
    }

    @Test
    void file200MbRoundTripsWithIdenticalHashInStreaming() throws Exception {
        long size = 200L * MIB + 12_345;
        long seed = 200;
        String key = StorageKeys.newKey();
        log.info("JVM max heap: {} MiB", Runtime.getRuntime().maxMemory() / MIB);

        MessageDigest sent = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(GeneratedData.stream(seed, 0, size), sent)) {
            assertThat(s3.put(key, in, size, "video/mp4")).isEqualTo(size);
        }
        String etag = s3.client.headObject(b -> b.bucket(s3.bucket).key(key)).eTag();
        assertThat(etag).as("obiect mare: multipart intern (ETag „…-N”)").endsWith("-13\"");

        String expected = hex(sent.digest());
        String actual = sha256(s3.get(key, ByteRange.full()));
        log.info("200 MB: SHA-256 trimis {} / citit {}", expected, actual);
        assertThat(actual).isEqualTo(expected);
        assertThat(s3.pendingUploads(key)).isEmpty();

        // Descărcare parțială: exact octeții ceruți, inclusiv peste granița părților de 16 MiB și la sfârșit.
        assertRange(key, seed, ByteRange.of(123_456_789, 1_000), 1_000);
        assertRange(key, seed, ByteRange.of(16L * MIB - 10, 20), 20);
        assertRange(key, seed, ByteRange.from(size - 777), 777);
        assertRange(key, seed, ByteRange.of(size - 5, 1_000), 5);
        try (InputStream in = s3.get(key, ByteRange.from(size))) {
            assertThat(in.readAllBytes()).isEmpty();
        }
        // Închiderea înainte de sfârșit (client care renunță) nu blochează și nu golește restul obiectului.
        try (InputStream in = s3.get(key, ByteRange.full())) {
            assertThat(in.readNBytes(1_000)).isEqualTo(GeneratedData.bytes(seed, 0, 1_000));
        }
    }

    @Test
    void chunkedUploadWithResentAndInterruptedChunkHasIdenticalHash() throws Exception {
        int chunk = 16 * MIB;
        long size = 3L * chunk + 3_000_001;
        long seed = 16;
        String key = StorageKeys.newKey();
        ChunkedUpload upload = new ChunkedUpload(key, s3.open(key, size, chunk), size, chunk);
        assertThat(upload.totalChunks()).isEqualTo(4);

        List<ChunkReceipt> receipts = new ArrayList<>();
        receipts.add(append(upload, 1, seed));
        // Bucata 2: prima trimitere are alt conținut, a doua se întrerupe la jumătate, a treia e cea bună.
        append(upload, 2, seed + 1);
        assertThatThrownBy(() -> s3.append(upload, 2, GeneratedData.interrupted(
                GeneratedData.stream(seed, upload.offsetOf(2), chunk), chunk / 2), chunk))
                .isInstanceOf(StorageException.class)
                .isNotInstanceOf(StorageUnavailableException.class)
                .hasMessageContaining("întreruptă");
        receipts.add(append(upload, 2, seed));
        receipts.add(append(upload, 3, seed));
        receipts.add(append(upload, 4, seed));
        assertThat(receipts.get(3).size()).isEqualTo(3_000_001);
        assertThat(s3.exists(key)).as("invizibil până la finish").isFalse();

        assertThat(s3.finish(upload, receipts)).isEqualTo(size);
        assertThatCode(() -> s3.finish(upload, receipts)).as("finish idempotent").doesNotThrowAnyException();

        String expected = sha256(GeneratedData.stream(seed, 0, size));
        String actual = sha256(s3.get(key, ByteRange.full()));
        log.info("Upload în bucăți 3 x 16 MiB + 3000001: SHA-256 așteptat {} / citit {}", expected, actual);
        assertThat(actual).isEqualTo(expected);
        assertRange(key, seed, ByteRange.of(2L * chunk - 50, 100), 100);
        assertThat(s3.pendingUploads(key)).isEmpty();
    }

    @Test
    void abortLeavesNoUnfinishedMultipartUpload() {
        int chunk = 5 * MIB;
        long size = 2L * chunk + 1;
        String key = StorageKeys.newKey();
        ChunkedUpload upload = new ChunkedUpload(key, s3.open(key, size, chunk), size, chunk);
        append(upload, 1, 5);
        assertThat(s3.pendingUploads(key)).hasSize(1);

        s3.abort(upload);

        assertThat(s3.pendingUploads(key)).isEmpty();
        assertThat(s3.exists(key)).isFalse();
        assertThatCode(() -> s3.abort(upload)).as("abort idempotent").doesNotThrowAnyException();
        assertThatThrownBy(() -> s3.append(upload, 2, GeneratedData.stream(5, chunk, chunk), chunk))
                .as("o bucată după abort").isInstanceOf(StorageException.class);
    }

    @Test
    void largePutWithShortSourceAbortsTheInternalMultipart() {
        String key = StorageKeys.newKey();

        assertThatThrownBy(() -> s3.put(key, GeneratedData.stream(7, 0, 20L * MIB), 40L * MIB, null))
                .isInstanceOf(StorageException.class)
                .isNotInstanceOf(StorageUnavailableException.class)
                .hasMessageContaining("sursa s-a terminat");
        assertThat(s3.exists(key)).isFalse();
        assertThat(s3.pendingUploads(key)).isEmpty();
    }

    @Test
    void deleteAlsoAbortsUnfinishedUploadsForTheKey() {
        String key = StorageKeys.newKey();
        ChunkedUpload upload = new ChunkedUpload(key, s3.open(key, 6L * MIB, 5 * MIB), 6L * MIB, 5 * MIB);
        append(upload, 1, 9);

        assertThat(s3.delete(key)).isTrue();
        assertThat(s3.pendingUploads(key)).isEmpty();
        assertThat(s3.delete(key)).isFalse();
    }

    // --- utilitare -------------------------------------------------------------------------------------------

    private static ChunkReceipt append(ChunkedUpload upload, int n, long seed) {
        long length = upload.expectedLength(n);
        ChunkReceipt receipt = s3.append(upload, n, GeneratedData.stream(seed, upload.offsetOf(n), length), length);
        assertThat(receipt.n()).isEqualTo(n);
        assertThat(receipt.size()).isEqualTo(length);
        assertThat(receipt.etag()).isNotBlank();
        return receipt;
    }

    private static void assertRange(String key, long seed, ByteRange range, int expectedLength) throws IOException {
        try (InputStream in = s3.get(key, range)) {
            assertThat(in.readAllBytes()).as("Range " + S3Adapter.rangeHeader(range))
                    .isEqualTo(GeneratedData.bytes(seed, range.offset(), expectedLength));
        }
    }

    private static String sha256(InputStream stream) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = stream) {
            byte[] buffer = new byte[64 * 1024];
            for (int r; (r = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, r);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
