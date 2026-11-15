package ro.filegrid.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageHealth;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageUnavailableException;

/** No MinIO (always runs): storage stopped or unconfigured -> down / 503, fast and with no secrets in messages. */
class S3AdapterUnavailableTest {

    /** Test values, not real credentials. */
    private static final String ACCESS = "test-access-key";
    private static final String SECRET = "test-secret-value-not-real";

    @Test
    void stoppedMinioIsReportedDownQuicklyAndAsUnavailable() {
        // Port 1 on 127.0.0.1: connection refused immediately, like a stopped minio container.
        try (S3Adapter s3 = S3Adapter.fromProperties(
                S3Properties.of("http://127.0.0.1:1", null, "filegrid", ACCESS, SECRET))) {
            StorageHealth health = s3.health();

            assertThat(health.up()).isFalse();
            assertThat(health.storage()).isEqualTo(StorageType.S3);
            assertThat(health.latency()).isLessThan(Duration.ofSeconds(3));
            assertThat(health.detail()).isNotBlank().doesNotContain(SECRET).doesNotContain(ACCESS);

            String key = StorageKeys.newKey();
            assertThatThrownBy(() -> s3.put(key, new ByteArrayInputStream(new byte[10]), 10, "text/plain"))
                    .isInstanceOf(StorageUnavailableException.class)
                    .satisfies(e -> assertThat(((StorageException) e).storage()).isEqualTo(StorageType.S3))
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET).doesNotContain(ACCESS));
            assertThatThrownBy(() -> s3.get(key, ByteRange.full())).isInstanceOf(StorageUnavailableException.class);
            assertThatThrownBy(() -> s3.exists(key)).isInstanceOf(StorageUnavailableException.class);
            assertThatThrownBy(() -> s3.open(key, 10, 5 * 1024 * 1024))
                    .isInstanceOf(StorageUnavailableException.class);
        }
    }

    @Test
    void missingConfigurationStartsAsDownInsteadOfFailing() {
        try (S3Adapter s3 = S3Adapter.fromProperties(S3Properties.of("http://minio:9000", null, "filegrid", "", null))) {
            StorageHealth health = s3.health();

            assertThat(health.up()).isFalse();
            assertThat(health.detail()).contains("S3_ACCESS_KEY", "S3_SECRET_KEY");
            assertThatThrownBy(() -> s3.exists(StorageKeys.newKey())).isInstanceOf(StorageUnavailableException.class);
        }
    }

    @Test
    void rangeHeaderFollowsHttpSyntax() {
        assertThat(S3Adapter.rangeHeader(ByteRange.of(0, 1))).isEqualTo("bytes=0-0");
        assertThat(S3Adapter.rangeHeader(ByteRange.of(1_000, 500))).isEqualTo("bytes=1000-1499");
        assertThat(S3Adapter.rangeHeader(ByteRange.from(42))).isEqualTo("bytes=42-");
        assertThat(S3Adapter.rangeHeader(ByteRange.of(10, Long.MAX_VALUE))).isEqualTo("bytes=10-");
    }

    @Test
    void chunkSizeBelowFiveMibIsRejectedForMultiChunkUploads() {
        try (S3Adapter s3 = S3Adapter.fromProperties(
                S3Properties.of("http://127.0.0.1:1", null, "filegrid", ACCESS, SECRET))) {
            assertThatThrownBy(() -> s3.open(StorageKeys.newKey(), 10L * 1024 * 1024, 1024 * 1024))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void propertiesNeverPrintTheSecret() {
        assertThat(S3Properties.of("http://minio:9000", null, "filegrid", ACCESS, SECRET).toString())
                .doesNotContain(SECRET);
    }
}
