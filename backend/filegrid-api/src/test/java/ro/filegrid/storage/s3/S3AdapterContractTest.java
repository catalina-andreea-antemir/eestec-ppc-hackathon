package ro.filegrid.storage.s3;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import ro.filegrid.storage.ChunkedWriter;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageAdapterContractTest;

/**
 * The shared contract test ({@link StorageAdapterContractTest}) run on {@link S3Adapter} against the stack's real MinIO
 * (see {@link S3TestSupport}); skipped if MinIO is not available.
 */
class S3AdapterContractTest extends StorageAdapterContractTest {

    private static S3TestSupport.TrackingS3Adapter s3;

    @BeforeAll
    static void connect() {
        s3 = S3TestSupport.connectOrSkip();
    }

    @AfterAll
    static void cleanup() {
        if (s3 != null) {
            s3.cleanupAndClose();
        }
    }

    @Override
    protected StorageAdapter adapter() {
        return s3;
    }

    @Override
    protected ChunkedWriter chunkedWriter() {
        return s3;
    }
}
