package ro.filegrid.storage.ftp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import ro.filegrid.storage.ChunkedWriter;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageAdapterContractTest;

/**
 * The shared contract test ({@link StorageAdapterContractTest}) run on {@link FtpAdapter} against the stack's real FTP
 * server (see {@link FtpTestSupport}); skipped if FTP is not available.
 */
class FtpAdapterContractTest extends StorageAdapterContractTest {

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

    @Override
    protected StorageAdapter adapter() {
        return ftp;
    }

    @Override
    protected ChunkedWriter chunkedWriter() {
        return ftp;
    }
}
