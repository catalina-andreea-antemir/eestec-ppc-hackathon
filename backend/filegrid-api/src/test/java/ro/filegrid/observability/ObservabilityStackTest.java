package ro.filegrid.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import ro.filegrid.FileGridApplication;
import ro.filegrid.security.Role;
import ro.filegrid.security.TokenService;

/**
 * C12 pe stack-ul real (SQL Server, MinIO, vsftpd; se sare curat fără el): după câteva operații, {@code
 * /actuator/prometheus} are seriile {@code filegrid_operation_seconds_bucket} cu etichetele corecte, în
 * {@code operation_metrics} apar rânduri cu scenariul din {@code X-Scenario}, benchmark-ul completează cele 12
 * combinații fără resturi în storage și {@code /stats/requirements} răspunde cu R1–R4. Datele de test se șterg.
 */
class ObservabilityStackTest {

    private static final String JDBC = "jdbc:sqlserver://127.0.0.1:1433;encrypt=true;trustServerCertificate=true;"
            + "loginTimeout=3;databaseName=";
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String ADMIN = "it-c12-admin-" + RUN;
    private static final String DISP = "it-c12-disp-" + RUN;
    private static final String FIELD = "it-c12-field-" + RUN;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void metricsOperationMetricsBenchmarkAndRequirementsOnTheRealStack() throws Exception {
        Map<String, String> env = environment();
        Assumptions.assumeTrue(env.containsKey("META_DB_PASSWORD") && env.containsKey("STORAGE_DB_PASSWORD")
                        && env.containsKey("S3_ACCESS_KEY") && env.containsKey("S3_SECRET_KEY")
                        && env.containsKey("FTP_USER") && env.containsKey("FTP_PASSWORD"),
                "Lipsesc parolele stack-ului (mediu sau .env); test sarit");
        for (int port : new int[] {1433, 9000, 21}) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(500);
            } catch (IOException e) {
                Assumptions.abort("Stack-ul nu răspunde pe 127.0.0.1:" + port + "; test sarit");
            }
        }
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0", "--spring.profiles.active=db", "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + JDBC + "FileGridMeta", "--spring.datasource.username=filegrid_meta_app",
                "--spring.datasource.password=" + env.get("META_DB_PASSWORD"),
                "--filegrid.storage-db.url=" + JDBC + "FileGridStorage",
                "--filegrid.storage-db.username=filegrid_storage_app",
                "--filegrid.storage-db.password=" + env.get("STORAGE_DB_PASSWORD"),
                "--filegrid.s3.endpoint=" + env.getOrDefault("S3_TEST_ENDPOINT", "http://127.0.0.1:9000"),
                "--filegrid.s3.bucket=" + env.getOrDefault("MINIO_BUCKET", "filegrid"),
                "--filegrid.s3.access-key=" + env.get("S3_ACCESS_KEY"),
                "--filegrid.s3.secret-key=" + env.get("S3_SECRET_KEY"),
                "--filegrid.ftp.host=127.0.0.1", "--filegrid.ftp.user=" + env.get("FTP_USER"),
                "--filegrid.ftp.password=" + env.get("FTP_PASSWORD"),
                "--filegrid.encryption.key=" + Base64.getEncoder().encodeToString(random(32)),
                "--filegrid.security.jwt.secret=" + Base64.getEncoder().encodeToString(random(48)),
                "--filegrid.download.link-secret=" + Base64.getEncoder().encodeToString(random(48)),
                "--spring.main.banner-mode=off", "--logging.level.root=WARN"));
        HikariDataSource storageDb = new HikariDataSource();
        storageDb.setJdbcUrl(JDBC + "FileGridStorage");
        storageDb.setUsername("filegrid_storage_app");
        storageDb.setPassword(env.get("STORAGE_DB_PASSWORD"));
        storageDb.setMaximumPoolSize(2);
        JdbcTemplate storage = new JdbcTemplate(storageDb);
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(FileGridApplication.class)
                .run(args.toArray(new String[0]))) {
            NamedParameterJdbcTemplate meta = app.getBean(NamedParameterJdbcTemplate.class);
            JdbcTemplate metaPlain = meta.getJdbcTemplate();
            String since = metaPlain.queryForObject("SELECT CONVERT(varchar(33), SYSUTCDATETIME(), 126)", String.class);
            try {
                run(app, env, metaPlain, storage, since);
            } finally {
                metaPlain.update("DELETE FROM dbo.audit_log WHERE actor LIKE 'it-c12-%' OR file_id IN "
                        + "(SELECT id FROM dbo.files WHERE created_by LIKE 'it-c12-%')");
                metaPlain.update("DELETE FROM dbo.files WHERE created_by LIKE 'it-c12-%'");
                metaPlain.update("DELETE FROM dbo.operation_metrics WHERE ts >= CAST(? AS datetime2) AND "
                        + "(source = N'benchmark' OR scenario = N'it-c12')", since);
            }
        } finally {
            storageDb.close();
        }
    }

    private void run(ConfigurableApplicationContext app, Map<String, String> env, JdbcTemplate meta,
                     JdbcTemplate storage, String since) throws Exception {
        String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
        TokenService tokens = app.getBean(TokenService.class);
        String admin = tokens.issueAccessToken(ADMIN, Role.ADMIN);
        String disp = tokens.issueAccessToken(DISP, Role.DISPATCH);
        String field = tokens.issueAccessToken(FIELD, Role.FIELD);

        // --- operații cu X-Scenario: upload, download, verify --------------------------------------------------
        byte[] content = ("C12-" + RUN + "\n").repeat(2_000).getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> created = upload(base, admin, content);
        assertThat(created.statusCode()).as(new String(created.body(), StandardCharsets.UTF_8)).isEqualTo(201);
        assertThat(created.headers().firstValue("X-Trace-Id")).isPresent();
        String id = JSON.readTree(created.body()).get("id").asText();
        assertThat(get(base, "/api/v1/files/" + id + "/download", admin).statusCode()).isEqualTo(200);
        assertThat(get(base, "/api/v1/files/" + id + "/verify", admin).statusCode()).isEqualTo(200);
        HttpResponse<byte[]> noAuth = get(base, "/api/v1/files/" + id + "/verify", null);
        assertThat(noAuth.statusCode()).isEqualTo(401);
        assertThat(noAuth.headers().firstValue("X-Trace-Id")).isPresent();

        // --- Prometheus: serii cu etichetele corecte ------------------------------------------------------------
        String scrape = new String(get(base, "/actuator/prometheus", null).body(), StandardCharsets.UTF_8);
        for (String op : List.of("upload", "download", "verify")) {
            assertThat(scrape).as(op).containsPattern("filegrid_operation_seconds_bucket\\{[^}]*category=\"CONNECTION_DOC\""
                    + "[^}]*operation=\"" + op + "\"[^}]*outcome=\"ok\"[^}]*storage=\"[A-Z0-9]+\"[^}]*\\}");
        }
        assertThat(scrape).contains("filegrid_operation_seconds_count{", "filegrid_operation_seconds_sum{",
                "filegrid_meter_unconfirmed ", "filegrid_opmetrics_dropped_total ", "filegrid_storage_up{storage=\"DB\"}");

        // --- operation_metrics: rânduri cu scenariul din X-Scenario ---------------------------------------------
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(meta.queryForObject(
                "SELECT COUNT(*) FROM dbo.operation_metrics WHERE scenario = N'it-c12' AND source = N'live' "
                        + "AND category = N'CONNECTION_DOC' AND ts >= CAST(? AS datetime2)", Integer.class, since))
                .isGreaterThanOrEqualTo(3));
        assertThat(meta.queryForList("SELECT DISTINCT operation FROM dbo.operation_metrics WHERE scenario = N'it-c12' "
                + "AND ts >= CAST(? AS datetime2)", String.class, since)).contains("upload", "download", "verify");

        // --- /stats/requirements --------------------------------------------------------------------------------
        HttpResponse<byte[]> req = get(base, "/api/v1/stats/requirements", disp);
        assertThat(req.statusCode()).isEqualTo(200);
        JsonNode list = JSON.readTree(req.body()).get("requirements");
        assertThat(list).hasSize(4);
        assertThat(list.findValuesAsText("id")).containsExactly("R1", "R2", "R3", "R4");
        list.forEach(r -> assertThat(r.get("status").asText()).isIn("OK", "AT_RISK"));
        assertThat(get(base, "/api/v1/stats/requirements", field).statusCode()).isEqualTo(403);
        assertThat(get(base, "/api/v1/stats/requirements", admin).statusCode()).isEqualTo(200);

        // --- benchmark: 12 combinații, fără resturi în storage ---------------------------------------------------
        S3Client s3 = S3Client.builder().endpointOverride(URI.create(env.getOrDefault("S3_TEST_ENDPOINT", "http://127.0.0.1:9000")))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(env.get("S3_ACCESS_KEY"), env.get("S3_SECRET_KEY")))).build();
        String bucket = env.getOrDefault("MINIO_BUCKET", "filegrid");
        int s3Before = s3Objects(s3, bucket);
        int ftpBefore = ftpObjects(env);
        int blobsBefore = storage.queryForObject("SELECT COUNT(*) FROM dbo.blob_versions", Integer.class);
        long chunksBefore = storage.queryForObject("SELECT COUNT_BIG(*) FROM dbo.blob_chunks", Long.class);

        assertThat(post(base, "/api/v1/admin/benchmark/run", disp).statusCode()).isEqualTo(403);
        HttpResponse<byte[]> bench = post(base, "/api/v1/admin/benchmark/run", admin);
        assertThat(bench.statusCode()).as(new String(bench.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        JsonNode result = JSON.readTree(bench.body());
        assertThat(result.get("source").asText()).isEqualTo("benchmark");
        assertThat(result.get("scenario").asText()).isEqualTo("benchmark");
        assertThat(result.get("results")).hasSize(12);
        result.get("results").forEach(r -> {
            assertThat(r.get("errors").asInt()).as(r.toString()).isZero();
            assertThat(r.get("latencyP95Ms").asDouble()).isPositive();
        });
        assertThat(result.get("results").findValuesAsText("category")).containsOnly("METER_READING", "OUTAGE_RECORD",
                "CONNECTION_DOC", "INSPECTION_VIDEO");
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(meta.queryForObject(
                "SELECT COUNT(DISTINCT CONCAT(category, storage)) FROM dbo.operation_metrics WHERE source = N'benchmark' "
                        + "AND scenario = N'benchmark' AND ts >= CAST(? AS datetime2)", Integer.class, since))
                .isEqualTo(12));
        assertThat(s3Objects(s3, bucket)).isEqualTo(s3Before);
        assertThat(ftpObjects(env)).isEqualTo(ftpBefore);
        assertThat(storage.queryForObject("SELECT COUNT(*) FROM dbo.blob_versions", Integer.class)).isEqualTo(blobsBefore);
        assertThat(storage.queryForObject("SELECT COUNT_BIG(*) FROM dbo.blob_chunks", Long.class)).isEqualTo(chunksBefore);
    }

    private static int s3Objects(S3Client s3, String bucket) {
        return s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).build()).contents().stream()
                .mapToInt(o -> 1).sum();
    }

    private static int ftpObjects(Map<String, String> env) throws IOException {
        FTPClient ftp = new FTPClient();
        try {
            ftp.setConnectTimeout(3000);
            ftp.connect("127.0.0.1", 21);
            Assumptions.assumeTrue(ftp.login(env.get("FTP_USER"), env.get("FTP_PASSWORD")), "FTP login");
            ftp.enterLocalPassiveMode();
            String[] names = ftp.listNames("/o");
            return names == null ? 0 : names.length;
        } finally {
            if (ftp.isConnected()) {
                ftp.disconnect();
            }
        }
    }

    private HttpResponse<byte[]> upload(String base, String token, byte[] content) throws Exception {
        String boundary = "----c12" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"category\"\r\n\r\nCONNECTION_DOC"
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"c12.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/files"))
                .header("Authorization", "Bearer " + token).header("X-Scenario", "it-c12")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> get(String base, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("X-Scenario", "it-c12");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> post(String base, String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /** Variabilele de mediu, completate cu {@code .env} din rădăcina repo-ului (mediul are prioritate). */
    private static Map<String, String> environment() {
        Map<String, String> values = new HashMap<>();
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path env = dir.resolve(".env");
            if (Files.isRegularFile(env) && Files.isDirectory(dir.resolve("backend"))) {
                try {
                    for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
                        int eq = line.indexOf('=');
                        if (eq > 0 && !line.startsWith("#") && eq < line.length() - 1) {
                            values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                        }
                    }
                } catch (IOException e) {
                    // Fără .env lizibil rămân variabilele de mediu.
                }
                break;
            }
        }
        System.getenv().forEach((k, v) -> {
            if (!v.isBlank()) {
                values.put(k, v);
            }
        });
        return values;
    }
}
