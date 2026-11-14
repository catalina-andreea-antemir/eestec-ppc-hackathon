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
 * Legătura testelor de integrare cu MinIO-ul real din stack. Pornire: {@code ./scripts/start.sh}, apoi porturile de
 * dezvoltare ({@code docker compose -f docker-compose.yml -f infra/docker/docker-compose.dev.yml up -d}: S3 API pe
 * {@code 127.0.0.1:9000}).
 *
 * <p>Credențialele sunt ale utilizatorului aplicației ({@code S3_ACCESS_KEY} / {@code S3_SECRET_KEY}), din variabilele
 * de mediu sau din {@code .env} din rădăcina repo-ului (nu se afișează niciodată). Endpoint-ul: {@code S3_TEST_ENDPOINT}
 * sau {@code http://127.0.0.1:9000}; bucket-ul: {@code MINIO_BUCKET} sau {@code filegrid}. Dacă lipsesc credențialele
 * sau MinIO nu răspunde, testele se <b>sar</b> (assumption), nu pică: {@code ./mvnw test} merge și fără stack.
 */
final class S3TestSupport {

    static final String DEFAULT_ENDPOINT = "http://127.0.0.1:9000";

    private S3TestSupport() {
    }

    /** Adaptorul conectat la MinIO, sau sare testele clasei dacă MinIO nu e disponibil. */
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

    /** Variabilele de mediu, completate cu {@code .env} din rădăcina repo-ului (mediul are prioritate). */
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
                // Fără .env lizibil: rămân variabilele de mediu.
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
     * {@link S3Adapter} care ține minte cheile create de teste (ca să le șteargă la final) și dă acces la clientul S3
     * pentru verificări (upload-uri multipart neterminate, ETag).
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

        /** Upload-urile multipart neterminate pentru exact această cheie. */
        List<MultipartUpload> pendingUploads(String key) {
            return client.listMultipartUploadsPaginator(b -> b.bucket(bucket).prefix(key)).uploads().stream()
                    .filter(u -> key.equals(u.key()))
                    .toList();
        }

        /** Șterge obiectele și upload-urile neterminate create de teste, apoi închide clientul. */
        void cleanupAndClose() {
            try {
                for (String key : keys) {
                    try {
                        delete(key);
                    } catch (RuntimeException e) {
                        // Curățare cât se poate; nu ascunde rezultatul testelor.
                    }
                }
            } finally {
                close();
            }
        }
    }
}
