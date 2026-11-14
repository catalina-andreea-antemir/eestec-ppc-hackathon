package ro.filegrid.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.Test;

import ro.filegrid.domain.StorageType;

/** Adaptorul în memorie respectă contractul (și arată cum se extinde {@link StorageAdapterContractTest}). */
class InMemoryStorageAdapterTest extends StorageAdapterContractTest {

    private final InMemoryStorageAdapter storage = new InMemoryStorageAdapter(StorageType.DB);

    @Override
    protected StorageAdapter adapter() {
        return storage;
    }

    @Override
    protected ChunkedWriter chunkedWriter() {
        return storage;
    }

    @Test
    void storageDownIsReportedAsUnavailable() {
        storage.setUp(false);

        assertThat(storage.health().up()).isFalse();
        assertThatThrownBy(() -> storage.put(StorageKeys.newKey(), new ByteArrayInputStream(new byte[1]), 1, "x"))
                .isInstanceOf(StorageUnavailableException.class)
                .extracting(e -> ((StorageException) e).storage()).isEqualTo(StorageType.DB);
    }
}
