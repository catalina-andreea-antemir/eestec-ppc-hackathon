package ro.filegrid.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import ro.filegrid.domain.StorageType;

class StorageTypesTest {

    private static final int MIB = 1024 * 1024;

    @Test
    void chunkedUploadComputesChunksOffsetsAndLastChunkLength() {
        ChunkedUpload upload = new ChunkedUpload(StorageKeys.newKey(), "ref", 1_181_116_006L, 16 * MIB);

        assertThat(upload.totalChunks()).isEqualTo(71);
        assertThat(upload.offsetOf(1)).isZero();
        assertThat(upload.offsetOf(36)).isEqualTo(35L * 16 * MIB);
        assertThat(upload.expectedLength(1)).isEqualTo(16 * MIB);
        assertThat(upload.expectedLength(71)).isEqualTo(1_181_116_006L - 70L * 16 * MIB);
        assertThatThrownBy(() -> upload.offsetOf(72)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> upload.offsetOf(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactMultipleHasNoExtraChunk() {
        ChunkedUpload upload = new ChunkedUpload(StorageKeys.newKey(), "ref", 10L * MIB, 5 * MIB);

        assertThat(upload.totalChunks()).isEqualTo(2);
        assertThat(upload.expectedLength(2)).isEqualTo(5 * MIB);
    }

    @Test
    void byteRangeLengthWithinObject() {
        assertThat(ByteRange.full().lengthWithin(100)).isEqualTo(100);
        assertThat(ByteRange.from(40).lengthWithin(100)).isEqualTo(60);
        assertThat(ByteRange.of(90, 50).lengthWithin(100)).isEqualTo(10);
        assertThat(ByteRange.from(100).lengthWithin(100)).isZero();
        assertThat(ByteRange.from(0).isFull()).isTrue();
        assertThatThrownBy(() -> ByteRange.of(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ByteRange.of(-1, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysAreUuidsAndUnsafeKeysAreRejected() {
        assertThat(StorageKeys.requireValid(StorageKeys.newKey())).hasSize(36);
        for (String bad : new String[] {"", "../etc/passwd", "a/b", " x", "x".repeat(129), null}) {
            assertThatThrownBy(() -> StorageKeys.requireValid(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void registryFindsAdaptersByTypeAndRejectsDuplicates() {
        InMemoryStorageAdapter db = new InMemoryStorageAdapter(StorageType.DB);
        StorageAdapterRegistry registry = new StorageAdapterRegistry(List.of(db), List.of(db));

        assertThat(registry.adapter(StorageType.DB)).isSameAs(db);
        assertThat(registry.chunkedWriter(StorageType.DB)).isSameAs(db);
        assertThat(registry.availableTypes()).containsExactly(StorageType.DB);
        assertThatThrownBy(() -> registry.adapter(StorageType.FTP)).isInstanceOf(StorageUnavailableException.class);
        assertThatThrownBy(() -> new StorageAdapterRegistry(List.of(db, new InMemoryStorageAdapter(StorageType.DB)),
                List.of())).isInstanceOf(IllegalStateException.class);
    }
}
