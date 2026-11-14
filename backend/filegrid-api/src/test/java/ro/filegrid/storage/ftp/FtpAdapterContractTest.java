package ro.filegrid.storage.ftp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import ro.filegrid.storage.ChunkedWriter;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageAdapterContractTest;

/**
 * Testul de contract comun ({@link StorageAdapterContractTest}) rulat pe {@link FtpAdapter}, contra serverului FTP real
 * din stack (vezi {@link FtpTestSupport}); se sare dacă FTP nu e disponibil.
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
