package ro.filegrid.storage.ftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.ChunkReceipt;
import ro.filegrid.storage.ChunkedUpload;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageHealth;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageUnavailableException;

/** Fără server FTP (rulează mereu): storage oprit sau neconfigurat → down / 503, rapid și fără secrete în mesaje. */
class FtpAdapterUnavailableTest {

    /** Valori de test, nu credențiale reale. */
    private static final String USER = "test-user";
    private static final String SECRET = "test-password-not-real";

    private static FtpAdapter stopped() {
        // Portul 1 pe 127.0.0.1: conexiune refuzată imediat, ca un container ftp oprit.
        return FtpAdapter.fromProperties(FtpProperties.of("127.0.0.1", 1, USER, SECRET));
    }

    @Test
    void stoppedServerIsReportedDownQuicklyAndAsUnavailable() {
        FtpAdapter ftp = stopped();
        StorageHealth health = ftp.health();

        assertThat(health.up()).isFalse();
        assertThat(health.storage()).isEqualTo(StorageType.FTP);
        assertThat(health.latency()).isLessThan(Duration.ofSeconds(3));
        assertThat(health.detail()).isNotBlank().doesNotContain(SECRET).doesNotContain(USER);

        String key = StorageKeys.newKey();
        ChunkedUpload upload = new ChunkedUpload(key, "u/" + key, 10, 10);
        assertUnavailable(() -> ftp.put(key, new ByteArrayInputStream(new byte[10]), 10, "text/plain"));
        assertUnavailable(() -> ftp.get(key, ByteRange.full()));
        assertUnavailable(() -> ftp.exists(key));
        assertUnavailable(() -> ftp.delete(key));
        assertUnavailable(() -> ftp.open(key, 10, 10));
        assertUnavailable(() -> ftp.append(upload, 1, new ByteArrayInputStream(new byte[10]), 10));
        assertUnavailable(() -> ftp.finish(upload, List.of(new ChunkReceipt(1, 10, null))));
        assertUnavailable(() -> ftp.abort(upload));
    }

    @Test
    void missingConfigurationStartsAsDownInsteadOfFailing() {
        FtpAdapter ftp = FtpAdapter.fromProperties(FtpProperties.of("ftp", 21, "", null));
        StorageHealth health = ftp.health();

        assertThat(health.up()).isFalse();
        assertThat(health.detail()).contains("FTP_USER", "FTP_PASSWORD");
        assertUnavailable(() -> ftp.exists(StorageKeys.newKey()));
    }

    @Test
    void propertiesNeverPrintThePassword() {
        assertThat(FtpProperties.of("ftp", 21, USER, SECRET).toString()).doesNotContain(SECRET);
        assertThat(FtpProperties.of("ftp", 21, USER, SECRET).healthTimeout()).isLessThanOrEqualTo(Duration.ofSeconds(2));
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(StorageUnavailableException.class)
                .satisfies(e -> assertThat(((StorageException) e).storage()).isEqualTo(StorageType.FTP))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET).doesNotContain(USER));
    }
}
