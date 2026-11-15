package ro.filegrid.storage.s3;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Assumptions;

import ro.filegrid.storage.StorageHealth;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.MultipartUpload;

/**
 * Glue between the integration tests and the stack's real MinIO. Start: {@code ./scripts/start.sh}, then the
 * development ports ({@code docker compose -f docker-compose.yml -f infra/docker/docker-compose.dev.yml up -d}: S3 API on
 * {@code 127.0.0.1:9000}).
 *
 * <p>Credentials belong to the app user ({@code S3_ACCESS_KEY} / {@code S3_SECRET_KEY}), from environment variables or
 * the repo-root {@code .env} (never printed). Endpoint: {@code S3_TEST_ENDPOINT} or {@code http://127.0.0.1:9000};
 * bucket: {@code MINIO_BUCKET} or {@code filegrid}. If credentials are missing or MinIO does not answer, the tests are
 * <b>skipped</b> (assumption) instead of failing: {@code ./mvnw test} works without the stack.
 */
final class S3TestSupport {

    static final String DEFAULT_ENDPOINT = "http://127.0.0.1:9000";

    private S3TestSupport() {
    }

    /** Adapter connected to MinIO, or skips the class's tests if MinIO is not available. */
    static TrackingS3Adapter connectOrSkip() {
        Map<String, String> env = environment();
        String endpoint = env.getOrDefault("S3_TEST_ENDPOINT", DEFAULT_ENDPOINT);
        String bucket = env.getOrDefault("MINIO_BUCKET", "filegrid");
        S3Properties props = S3Properties.of(endpoint, "us-east-1", bucket, env.get("S3_ACCESS_KEY"),
                env.get("S3_SECRET_KEY"));
        Assumptions.assumeTrue(props.isConfigured(),
                "MinIO: lipsesc credentialele S3_ACCESS_KEY / S3_SECRET_KEY (mediu sau .env); test sarit");
        S3Client client = S3Adapter.buildClient(props);
        TrackingS3Adapter adapter = new TrackingS3Adapter(client, bucket, props);
        StorageHealth health = adapter.health();
        if (!health.up()) {
            adapter.close();
        }
        Assumptions.assumeTrue(health.up(), () -> "MinIO indisponibil la " + endpoint + " (" + health.detail()
                + "); test sarit. Porneste stack-ul si docker-compose.dev.yml.");
        return adapter;
    }

    /** Environment variables, completed from the repo-root {@code .env} (the environment wins). */
    static Map<String, String> environment() {
        Map<String, String> values = new HashMap<>();
        Path dotEnv = findDotEnv();
        if (dotEnv != null) {
            try {
                for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
                    String l = line.strip();
                    int eq = l.indexOf('=');
                    if (l.isEmpty() || l.startsWith("#") || eq <= 0) {
                        continue;
                    }
                    String value = l.substring(eq + 1).strip();
                    if (!value.isEmpty()) {
                        values.put(l.substring(0, eq).strip(), value);
                    }
                }
            } catch (IOException e) {
                // Without a readable .env, only the environment variables remain.
            }
        }
        for (String name : List.of("S3_TEST_ENDPOINT", "MINIO_BUCKET", "S3_ACCESS_KEY", "S3_SECRET_KEY")) {
            String v = System.getenv(name);
            if (v != null && !v.isBlank()) {
                values.put(name, v.strip());
            }
        }
        return values;
    }

    private static Path findDotEnv() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(".env")) && Files.isRegularFile(dir.resolve("docker-compose.yml"))) {
                return dir.resolve(".env");
            }
        }
        return null;
    }

    /**
     * {@link S3Adapter} that remembers the keys created by tests (to delete them at the end) and exposes the S3 client
     * for checks (unfinished multipart uploads, ETag).
     */
    static final class TrackingS3Adapter extends S3Adapter {

        final S3Client client;
        final String bucket;
        final Set<String> keys = ConcurrentHashMap.newKeySet();

        TrackingS3Adapter(S3Client client, String bucket, S3Properties props) {
            super(client, bucket, props.partSize().toBytes(), props.operationTimeout(), props.healthTimeout());
            this.client = client;
            this.bucket = bucket;
        }

        @Override
        public long put(String key, InputStream data, long length, String contentType) {
            keys.add(key);
            return super.put(key, data, length, contentType);
        }

        @Override
        public String open(String key, long totalSize, int chunkSize) {
            keys.add(key);
            return super.open(key, totalSize, chunkSize);
        }

        /** Unfinished multipart uploads for exactly this key. */
        List<MultipartUpload> pendingUploads(String key) {
            return client.listMultipartUploadsPaginator(b -> b.bucket(bucket).prefix(key)).uploads().stream()
                    .filter(u -> key.equals(u.key()))
                    .toList();
        }

        /** Deletes the objects and unfinished uploads created by tests, then closes the client. */
        void cleanupAndClose() {
            try {
                for (String key : keys) {
                    try {
                        delete(key);
                    } catch (RuntimeException e) {
                        // Best-effort cleanup; must not hide the test outcome.
                    }
                }
            } finally {
                close();
            }
        }
    }
}
