package ro.filegrid.storage.ftp;

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
 * C6 acceptance criteria on the real FTP server (skipped without a server): a 200 MB file uploaded and downloaded as
 * a stream with an identical hash (also run with {@code -DargLine=-Xmx256m}), exact partial download, a chunked upload
 * of 16 MiB chunks with one resent chunk, one cut off by the source and one whose connection drops mid-write, and an
 * abort that leaves no files behind. Data comes from {@link GeneratedData}, so nothing file-sized is held in memory.
 */
class FtpAdapterLargeObjectTest {

    private static final Logger log = LoggerFactory.getLogger(FtpAdapterLargeObjectTest.class);
    private static final int MIB = 1024 * 1024;

    private static FtpTestSupport.TrackingFtpAdapter ftp;

    @BeforeAll
    static void connect() {
        ftp = FtpTestSupport.connectOrSkip();
    }

    @AfterAll
    static void cleanup() {
        if (ftp != null) {
            ftp.cleanup();
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
            assertThat(ftp.put(key, in, size, "video/mp4")).isEqualTo(size);
        }
        assertThat(ftp.exists(key)).isTrue();

        String expected = hex(sent.digest());
        String actual = sha256(ftp.get(key, ByteRange.full()));
        log.info("200 MB: SHA-256 trimis {} / citit {}", expected, actual);
        assertThat(actual).isEqualTo(expected);

        // Partial download (REST): exactly the requested bytes, including at the end.
        assertRange(key, seed, ByteRange.of(123_456_789, 1_000), 1_000);
        assertRange(key, seed, ByteRange.from(size - 777), 777);
        assertRange(key, seed, ByteRange.of(size - 5, 1_000), 5);
        try (InputStream in = ftp.get(key, ByteRange.from(size))) {
            assertThat(in.readAllBytes()).isEmpty();
        }
        assertThat(FtpTestSupport.filesOf(ftp.props, key)).hasSize(1);
    }

    @Test
    void chunkedUploadWithResentInterruptedAndCutChunkHasIdenticalHash() throws Exception {
        int chunk = 16 * MIB;
        long size = 3L * chunk + 3_000_001;
        long seed = 16;
        String key = StorageKeys.newKey();
        ChunkedUpload upload = new ChunkedUpload(key, ftp.open(key, size, chunk), size, chunk);
        assertThat(upload.totalChunks()).isEqualTo(4);

        List<ChunkReceipt> receipts = new ArrayList<>();
        receipts.add(append(upload, 1, seed));
        // Chunk 2: the first send has different content (resent later), the second is cut off by the source midway.
        append(upload, 2, seed + 1);
        assertThatThrownBy(() -> ftp.append(upload, 2, GeneratedData.interrupted(
                GeneratedData.stream(seed, upload.offsetOf(2), chunk), chunk / 2), chunk))
                .isInstanceOf(StorageException.class)
                .isNotInstanceOf(StorageUnavailableException.class)
                .hasMessageContaining("întreruptă");
        receipts.add(append(upload, 2, seed));
        // Chunk 3: the data connection is cut abruptly midway (network down), then the chunk is resent.
        ftp.cutNextDataConnectionAfter(chunk / 2);
        assertThatThrownBy(() -> ftp.append(upload, 3, GeneratedData.stream(seed, upload.offsetOf(3), chunk), chunk))
                .isInstanceOf(StorageUnavailableException.class);
        assertThat(ftp.cutWasTriggered()).as("conexiunea a fost tăiată").isTrue();
        receipts.add(append(upload, 3, seed));
        receipts.add(append(upload, 4, seed));
        assertThat(receipts.get(3).size()).isEqualTo(3_000_001);
        assertThat(ftp.exists(key)).as("invizibil până la finish").isFalse();

        assertThat(ftp.finish(upload, receipts)).isEqualTo(size);
        assertThatCode(() -> ftp.finish(upload, receipts)).as("finish idempotent").doesNotThrowAnyException();
        assertThat(ftp.exists(key)).isTrue();

        String expected = sha256(GeneratedData.stream(seed, 0, size));
        String actual = sha256(ftp.get(key, ByteRange.full()));
        log.info("Upload în bucăți 3 x 16 MiB + 3000001: SHA-256 așteptat {} / citit {}", expected, actual);
        assertThat(actual).isEqualTo(expected);

        // Exact Range, including across chunk boundaries (and across several chunks at once).
        assertRange(key, seed, ByteRange.of(chunk - 50, 100), 100);
        assertRange(key, seed, ByteRange.of(2L * chunk - 50, 100), 100);
        assertRange(key, seed, ByteRange.of(chunk - 10, 2L * chunk + 20), (int) (2L * chunk + 20));
        assertRange(key, seed, ByteRange.of(3L * chunk - 1, 5_000), 5_000);
        assertRange(key, seed, ByteRange.from(size - 100), 100);
        assertRange(key, seed, ByteRange.of(size - 3, 100), 3);
        try (InputStream in = ftp.get(key, ByteRange.from(size))) {
            assertThat(in.readAllBytes()).isEmpty();
        }

        // On the server: only the 4 chunks and the manifest; the partial chunk was not left anywhere.
        assertThat(FtpTestSupport.filesOf(ftp.props, key)).hasSize(5).noneMatch(p -> p.contains(".part"))
                .noneMatch(p -> p.contains("~tmp"));
    }

    @Test
    void abortLeavesNoFilesOnTheServer() throws Exception {
        int chunk = 5 * MIB;
        long size = 2L * chunk + 1;
        String key = StorageKeys.newKey();
        ChunkedUpload upload = new ChunkedUpload(key, ftp.open(key, size, chunk), size, chunk);
        append(upload, 1, 5);
        // A chunk left partial, under a temp name.
        assertThatThrownBy(() -> ftp.append(upload, 2, GeneratedData.interrupted(
                GeneratedData.stream(5, chunk, chunk), 1_000), chunk)).isInstanceOf(StorageException.class);
        assertThat(FtpTestSupport.filesOf(ftp.props, key)).isNotEmpty();

        ftp.abort(upload);

        assertThat(FtpTestSupport.filesOf(ftp.props, key)).isEmpty();
        assertThat(ftp.exists(key)).isFalse();
        assertThatCode(() -> ftp.abort(upload)).as("abort idempotent").doesNotThrowAnyException();
        assertThatThrownBy(() -> ftp.append(upload, 2, GeneratedData.stream(5, chunk, chunk), chunk))
                .as("o bucată după abort").isInstanceOf(StorageException.class);
        assertThat(FtpTestSupport.filesOf(ftp.props, key)).isEmpty();
    }

    @Test
    void deleteRemovesCompleteChunkedObjectWithAllItsFiles() throws Exception {
        int chunk = 5 * MIB;
        long size = chunk + 10;
        String key = StorageKeys.newKey();
        ChunkedUpload upload = new ChunkedUpload(key, ftp.open(key, size, chunk), size, chunk);
        List<ChunkReceipt> receipts = List.of(append(upload, 1, 3), append(upload, 2, 3));
        ftp.finish(upload, receipts);
        assertThat(ftp.exists(key)).isTrue();

        assertThat(ftp.delete(key)).isTrue();

        assertThat(ftp.exists(key)).isFalse();
        assertThat(FtpTestSupport.filesOf(ftp.props, key)).isEmpty();
        assertThat(ftp.delete(key)).isFalse();
    }

    @Test
    void failedPutLeavesNothingBehind() throws Exception {
        String key = StorageKeys.newKey();

        assertThatThrownBy(() -> ftp.put(key, GeneratedData.stream(7, 0, 20L * MIB), 40L * MIB, null))
                .isInstanceOf(StorageException.class)
                .isNotInstanceOf(StorageUnavailableException.class)
                .hasMessageContaining("sursa s-a terminat");
        assertThat(ftp.exists(key)).isFalse();
        assertThat(FtpTestSupport.filesOf(ftp.props, key)).isEmpty();
    }

    @Test
    void streamsClosedEarlyDoNotLeaveConnectionsHanging() throws Exception {
        String key = StorageKeys.newKey();
        int size = 2 * MIB;
        ftp.put(key, GeneratedData.stream(3, 0, size), size, null);

        // vsftpd has max_clients=200: if an early close left connections hanging, this would fail along the way.
        for (int i = 0; i < 230; i++) {
            try (InputStream in = ftp.get(key, ByteRange.full())) {
                assertThat(in.readNBytes(100)).isEqualTo(GeneratedData.bytes(3, 0, 100));
            }
            try (InputStream in = ftp.get(key, ByteRange.of(1_000, 50))) {
                assertThat(in.readAllBytes()).isEqualTo(GeneratedData.bytes(3, 1_000, 50));
            }
            if (i % 50 == 0) {
                assertThat(ftp.health().up()).isTrue();
            }
        }
        assertThat(ftp.health().up()).isTrue();
        assertThat(sha256(ftp.get(key, ByteRange.full()))).isEqualTo(sha256(GeneratedData.stream(3, 0, size)));
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private static ChunkReceipt append(ChunkedUpload upload, int n, long seed) {
        long length = upload.expectedLength(n);
        ChunkReceipt receipt = ftp.append(upload, n, GeneratedData.stream(seed, upload.offsetOf(n), length), length);
        assertThat(receipt.n()).isEqualTo(n);
        assertThat(receipt.size()).isEqualTo(length);
        return receipt;
    }

    private static void assertRange(String key, long seed, ByteRange range, int expectedLength) throws IOException {
        try (InputStream in = ftp.get(key, range)) {
            assertThat(in.readAllBytes()).as("Range " + range)
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
