package ro.filegrid.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Testul de contract pentru {@link StorageAdapter} și {@link ChunkedWriter}: fiecare implementare (S3, FTP, DB) îl
 * extinde și dă adaptorul ei (de exemplu {@code DbStorageAdapterContractTest} cu un SQL Server pornit). Verifică
 * regulile din Javadoc-ul interfețelor: rescriere, interval, ștergere idempotentă, bucăți retrimise, finish
 * idempotent, abort.
 */
public abstract class StorageAdapterContractTest {

    /** Adaptorul testat. */
    protected abstract StorageAdapter adapter();

    /** Writer-ul testat (de obicei același obiect cu {@link #adapter()}). */
    protected abstract ChunkedWriter chunkedWriter();

    /** Mărimea bucății în testele de upload în bucăți; minim 5 MiB, ca la S3. */
    protected int chunkSize() {
        return 5 * 1024 * 1024;
    }

    @Test
    void putThenGetReturnsTheSameBytes() throws Exception {
        String key = StorageKeys.newKey();
        byte[] data = randomBytes(300_000, 1);

        long written = adapter().put(key, new ByteArrayInputStream(data), data.length, "application/octet-stream");

        assertThat(written).isEqualTo(data.length);
        assertThat(adapter().exists(key)).isTrue();
        assertThat(sha256(read(key, ByteRange.full()))).isEqualTo(sha256(data));
    }

    @Test
    void putReadsExactlyLengthBytesFromTheSource() throws Exception {
        String key = StorageKeys.newKey();
        byte[] data = randomBytes(10_000, 2);
        InputStream source = new ByteArrayInputStream(data);

        adapter().put(key, source, 4_000, "application/octet-stream");

        assertThat(read(key, ByteRange.full())).isEqualTo(Arrays.copyOf(data, 4_000));
        assertThat(source.available()).as("octeții de după length rămân necitiți").isEqualTo(6_000);
    }

    @Test
    void putWithShortSourceFailsAndLeavesNoObject() {
        String key = StorageKeys.newKey();

        assertThatThrownBy(() -> adapter().put(key, new ByteArrayInputStream(new byte[100]), 1_000, "text/plain"))
                .isInstanceOf(StorageException.class);
        assertThat(adapter().exists(key)).isFalse();
    }

    @Test
    void putReplacesAnExistingObject() throws Exception {
        String key = StorageKeys.newKey();
        adapter().put(key, new ByteArrayInputStream(randomBytes(5_000, 3)), 5_000, "text/plain");
        byte[] second = randomBytes(1_234, 4);

        adapter().put(key, new ByteArrayInputStream(second), second.length, "text/plain");

        assertThat(read(key, ByteRange.full())).isEqualTo(second);
    }

    @Test
    void getReturnsTheRequestedRange() throws Exception {
        String key = StorageKeys.newKey();
        byte[] data = randomBytes(100_000, 5);
        adapter().put(key, new ByteArrayInputStream(data), data.length, "application/octet-stream");

        assertThat(read(key, ByteRange.of(1_000, 500))).isEqualTo(Arrays.copyOfRange(data, 1_000, 1_500));
        assertThat(read(key, ByteRange.from(99_000))).isEqualTo(Arrays.copyOfRange(data, 99_000, 100_000));
        assertThat(read(key, ByteRange.of(99_900, 5_000))).as("intervalul se oprește la sfârșit")
                .isEqualTo(Arrays.copyOfRange(data, 99_900, 100_000));
        assertThat(read(key, ByteRange.from(100_000))).as("offset la sfârșit: stream gol").isEmpty();
    }

    @Test
    void getMissingKeyThrowsNotFound() {
        assertThatThrownBy(() -> adapter().get(StorageKeys.newKey(), ByteRange.full()))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    void deleteIsIdempotent() {
        String key = StorageKeys.newKey();
        adapter().put(key, new ByteArrayInputStream(new byte[10]), 10, "text/plain");

        assertThat(adapter().delete(key)).isTrue();
        assertThat(adapter().exists(key)).isFalse();
        assertThat(adapter().delete(key)).isFalse();
    }

    @Test
    void healthIsUp() {
        StorageHealth health = adapter().health();

        assertThat(health.storage()).isEqualTo(adapter().type());
        assertThat(health.up()).isTrue();
    }

    @Test
    void chunkedUploadProducesTheWholeObjectReadableWithRanges() throws Exception {
        int chunk = chunkSize();
        byte[] data = randomBytes(2 * chunk + 12_345, 6);
        ChunkedUpload upload = open(data.length);

        List<ChunkReceipt> receipts = appendAll(upload, data);
        assertThat(adapter().exists(upload.key())).as("invizibil până la finish").isFalse();
        long size = chunkedWriter().finish(upload, receipts);

        assertThat(size).isEqualTo(data.length);
        assertThat(sha256(read(upload.key(), ByteRange.full()))).isEqualTo(sha256(data));
        assertThat(read(upload.key(), ByteRange.of(chunk - 100, 200))).as("interval peste granița bucăților")
                .isEqualTo(Arrays.copyOfRange(data, chunk - 100, chunk + 100));
        assertThat(read(upload.key(), ByteRange.from(2L * chunk + 12_000)))
                .isEqualTo(Arrays.copyOfRange(data, 2 * chunk + 12_000, data.length));
    }

    @Test
    void resendingAChunkReplacesIt() throws Exception {
        int chunk = chunkSize();
        byte[] data = randomBytes(chunk + 1_000, 7);
        ChunkedUpload upload = open(data.length);
        List<ChunkReceipt> receipts = new ArrayList<>();

        receipts.add(append(upload, 1, data));
        // Prima trimitere a bucății 2 are alt conținut (de exemplu a căzut conexiunea); a doua e cea corectă.
        append(upload, 2, randomBytes(data.length, 8));
        receipts.add(append(upload, 2, data));
        chunkedWriter().finish(upload, receipts);

        assertThat(sha256(read(upload.key(), ByteRange.full()))).isEqualTo(sha256(data));
    }

    @Test
    void finishIsIdempotent() throws Exception {
        byte[] data = randomBytes(chunkSize() + 10, 9);
        ChunkedUpload upload = open(data.length);
        List<ChunkReceipt> receipts = appendAll(upload, data);
        chunkedWriter().finish(upload, receipts);

        assertThatCode(() -> chunkedWriter().finish(upload, receipts)).doesNotThrowAnyException();
        assertThat(sha256(read(upload.key(), ByteRange.full()))).isEqualTo(sha256(data));
    }

    @Test
    void abortRemovesEverythingAndIsIdempotent() {
        byte[] data = randomBytes(chunkSize() + 10, 10);
        ChunkedUpload upload = open(data.length);
        append(upload, 1, data);

        chunkedWriter().abort(upload);

        assertThat(adapter().exists(upload.key())).isFalse();
        assertThatCode(() -> chunkedWriter().abort(upload)).doesNotThrowAnyException();
    }

    // --- utilitare -------------------------------------------------------------------------------------------

    protected ChunkedUpload open(long totalSize) {
        String key = StorageKeys.newKey();
        String ref = chunkedWriter().open(key, totalSize, chunkSize());
        return new ChunkedUpload(key, ref, totalSize, chunkSize());
    }

    protected List<ChunkReceipt> appendAll(ChunkedUpload upload, byte[] data) {
        List<ChunkReceipt> receipts = new ArrayList<>();
        for (int n = 1; n <= upload.totalChunks(); n++) {
            receipts.add(append(upload, n, data));
        }
        return receipts;
    }

    protected ChunkReceipt append(ChunkedUpload upload, int n, byte[] data) {
        int offset = (int) upload.offsetOf(n);
        int length = (int) upload.expectedLength(n);
        ChunkReceipt receipt = chunkedWriter().append(upload, n, new ByteArrayInputStream(data, offset, length),
                length);
        assertThat(receipt.n()).isEqualTo(n);
        assertThat(receipt.size()).isEqualTo(length);
        return receipt;
    }

    protected byte[] read(String key, ByteRange range) throws IOException {
        try (InputStream in = adapter().get(key, range)) {
            return in.readAllBytes();
        }
    }

    protected static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    protected static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
