package ro.filegrid.storage.s3;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * {@code filegrid.s3} configuration (MinIO, C5), from {@code S3_ENDPOINT}, {@code S3_REGION}, {@code S3_BUCKET},
 * {@code S3_ACCESS_KEY}, {@code S3_SECRET_KEY} (set in compose; the credentials belong to the app user created by
 * {@code minio-init}, not the root account). No credential defaults: if they are missing the adapter starts
 * "unconfigured" ({@code health} = down, operations -> 503) while the app still starts.
 *
 * @param endpoint         S3 URL, e.g. {@code http://minio:9000} (path-style)
 * @param region           signing region (MinIO accepts any; default {@code us-east-1})
 * @param bucket           the bucket (created by {@code minio-init})
 * @param accessKey        the app user's access key
 * @param secretKey        the app user's secret key
 * @param connectTimeout   TCP connect timeout (default 2 s)
 * @param socketTimeout    socket read / write idle timeout (default 30 s)
 * @param operationTimeout total timeout for small operations: HEAD, DELETE, multipart create / complete / abort
 *                         (default 10 s); data transfers only have {@code socketTimeout}
 * @param healthTimeout    total timeout for {@code health()} (default 2 s, at most 2 s per the contract)
 * @param partSize         part size for {@code put} of large objects, which go through multipart internally
 *                         (default 16 MiB, minimum 5 MiB, the S3 / MinIO limit)
 * @param maxConnections   maximum HTTP connections in the pool (default 50)
 */
@ConfigurationProperties("filegrid.s3")
public record S3Properties(String endpoint, String region, String bucket, String accessKey, String secretKey,
        Duration connectTimeout, Duration socketTimeout, Duration operationTimeout, Duration healthTimeout,
        DataSize partSize, Integer maxConnections) {

    static final Duration MAX_HEALTH_TIMEOUT = Duration.ofSeconds(2);

    public S3Properties {
        region = isBlank(region) ? "us-east-1" : region.trim();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        socketTimeout = socketTimeout == null ? Duration.ofSeconds(30) : socketTimeout;
        operationTimeout = operationTimeout == null ? Duration.ofSeconds(10) : operationTimeout;
        healthTimeout = healthTimeout == null || healthTimeout.compareTo(MAX_HEALTH_TIMEOUT) > 0
                ? MAX_HEALTH_TIMEOUT : healthTimeout;
        partSize = partSize == null ? DataSize.ofMegabytes(16) : partSize;
        maxConnections = maxConnections == null ? 50 : maxConnections;
        if (partSize.toBytes() < S3Adapter.MIN_PART_SIZE) {
            throw new IllegalArgumentException("filegrid.s3.part-size trebuie să fie de cel puțin 5 MiB");
        }
    }

    /** Credentials and addresses for tests or code, everything else default. */
    public static S3Properties of(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        return new S3Properties(endpoint, region, bucket, accessKey, secretKey, null, null, null, null, null, null);
    }

    /** {@code true} if endpoint, bucket and both keys are set. */
    public boolean isConfigured() {
        return missing().isEmpty();
    }

    /** Names of the missing variables (no values), for the log and for {@code health}. */
    String missing() {
        StringBuilder sb = new StringBuilder();
        append(sb, endpoint, "S3_ENDPOINT");
        append(sb, bucket, "S3_BUCKET");
        append(sb, accessKey, "S3_ACCESS_KEY");
        append(sb, secretKey, "S3_SECRET_KEY");
        return sb.toString();
    }

    /** Without the secret key (the default record toString would print it). */
    @Override
    public String toString() {
        return "S3Properties[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + "]";
    }

    private static void append(StringBuilder sb, String value, String name) {
        if (isBlank(value)) {
            sb.append(sb.isEmpty() ? "" : ", ").append(name);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
