package ro.filegrid.download;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ro.filegrid.FileGridApplication;
import ro.filegrid.domain.StorageType;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.security.AuditLog;
import ro.filegrid.security.Role;
import ro.filegrid.security.TokenService;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageAdapterRegistry;

/**
 * C10 on the real stack, with CONNECTION_DOC on every storage (DB, S3, FTP): a document uploaded as CONNECTIONS and
 * read back with an identical plain hash, nothing in plaintext in storage (direct dump of {@code blob_chunks} for DB)
 * nor the file name in {@code files}, 401 without a token, 403 with the wrong role, 409 for a tampered segment,
 * {@code audit_log} rows for accesses and denials (valid hash chain), the {@code filegrid_doc_access_total} metric.
 * Data is deleted at the end. Without the stack, the test is <b>skipped</b>.
 */
class DocumentSecurityStackTest {

    private static final String JDBC = "jdbc:sqlserver://127.0.0.1:1433;encrypt=true;trustServerCertificate=true;"
            + "loginTimeout=3;databaseName=";
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String CONN = "it-c10-conn-" + RUN;
    private static final String DISP = "it-c10-disp-" + RUN;
    private static final String FIELD = "it-c10-field-" + RUN;
    private static final String ADMIN = "it-c10-admin-" + RUN;
    private static final String METER = "it-c10-meter-" + RUN;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @ParameterizedTest
    @EnumSource(StorageType.class)
    void connectionDocumentsAreEncryptedAuditedAndRoleChecked(StorageType type) throws Exception {
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
        Path policies = Files.createDirectory(temp.resolve("policies"));
        copyPolicies(policies, type);
        String docKey = Base64.getEncoder().encodeToString(random(32));

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
                "--filegrid.policies.location=" + policies,
                "--filegrid.encryption.key=" + docKey,
                "--filegrid.security.jwt.secret=" + Base64.getEncoder().encodeToString(random(48)),
                "--filegrid.download.link-secret=" + Base64.getEncoder().encodeToString(random(48)),
                "--spring.main.banner-mode=off", "--logging.level.root=WARN"));
        HikariDataSource storageDb = DataSourceBuilder.create().type(HikariDataSource.class)
                .url(JDBC + "FileGridStorage;responseBuffering=adaptive").username("filegrid_storage_app")
                .password(env.get("STORAGE_DB_PASSWORD")).build();
        storageDb.setMaximumPoolSize(2);
        storageDb.setInitializationFailTimeout(-1);
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(FileGridApplication.class)
                .run(args.toArray(new String[0]))) {
            NamedParameterJdbcTemplate meta = app.getBean(NamedParameterJdbcTemplate.class);
            List<String> keys = new ArrayList<>();
            try {
                run(app, type, meta, storageDb, keys);
            } finally {
                cleanUp(app, type, meta, storageDb, keys);
            }
        } finally {
            storageDb.close();
        }
    }

    private void run(ConfigurableApplicationContext app, StorageType type, NamedParameterJdbcTemplate meta,
                     HikariDataSource storageDb, List<String> keys) throws Exception {
        String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
        TokenService tokens = app.getBean(TokenService.class);
        Map<String, String> bearer = Map.of(
                CONN, tokens.issueAccessToken(CONN, Role.CONNECTIONS), DISP, tokens.issueAccessToken(DISP, Role.DISPATCH),
                FIELD, tokens.issueAccessToken(FIELD, Role.FIELD), ADMIN, tokens.issueAccessToken(ADMIN, Role.ADMIN),
                METER, tokens.issueAccessToken(METER, Role.METER_SERVICE));
        MeterRegistry metrics = app.getBean(MeterRegistry.class);
        StorageAdapter storage = app.getBean(StorageAdapterRegistry.class).adapter(type);

        String marker = "DOCUMENT-PERSONAL-" + UUID.randomUUID();
        String fileName = "cerere-racordare-" + UUID.randomUUID().toString().substring(0, 8) + "-Ionescu.txt";
        byte[] content = (marker + "\n").repeat(9_000).getBytes(StandardCharsets.UTF_8); // more than 3 segments of 64 KiB
        String plainHash = Sha256.hex(Sha256.of(content));

        // --- no token 401, wrong role 403 --------------------------------------------------------------------
        assertThat(send(base, "GET", "/api/v1/files?category=CONNECTION_DOC", null, null, null).status()).isEqualTo(401);
        assertThat(upload(base, bearer.get(DISP), "CONNECTION_DOC", fileName, content).status()).isEqualTo(403);
        assertThat(upload(base, bearer.get(FIELD), "CONNECTION_DOC", fileName, content).status()).isEqualTo(403);
        assertThat(upload(base, bearer.get(METER), "CONNECTION_DOC", fileName, content).status()).isEqualTo(403);
        assertThat(upload(base, bearer.get(CONN), "OUTAGE_RECORD", fileName, content).status()).isEqualTo(403);
        double deniedBefore = counter(metrics, "denied");

        // --- upload as CONNECTIONS -----------------------------------------------------------------------------
        Resp created = upload(base, bearer.get(CONN), "CONNECTION_DOC", fileName, content);
        assertThat(created.status()).as(new String(created.body(), StandardCharsets.UTF_8)).isEqualTo(201);
        JsonNode file = created.json();
        String id = file.get("id").asText();
        keys.add(file.get("id").asText());
        assertThat(file.get("encrypted").asBoolean()).isTrue();
        assertThat(file.get("originalName").asText()).isEqualTo(fileName);
        assertThat(file.get("sha256Plain").asText()).isEqualTo(plainHash);
        assertThat(file.get("sha256").asText()).isNotEqualTo(plainHash);
        assertThat(file.get("size").asLong()).isEqualTo(content.length);

        // --- nothing in plaintext in storage or in files -------------------------------------------------------
        String storageKey = meta.queryForObject("SELECT storage_key FROM dbo.files WHERE id = CAST(:id AS UNIQUEIDENTIFIER)",
                new MapSqlParameterSource("id", id), String.class);
        String storedName = meta.queryForObject("SELECT original_filename FROM dbo.files WHERE id = CAST(:id AS UNIQUEIDENTIFIER)",
                new MapSqlParameterSource("id", id), String.class);
        assertThat(storedName).doesNotContain("Ionescu").doesNotContain("cerere").doesNotContain(".txt");
        assertThat(storageKey).isEqualTo(UUID.fromString(storageKey).toString());
        byte[] raw;
        try (InputStream in = storage.get(storageKey, ByteRange.full())) {
            raw = in.readAllBytes();
        }
        assertThat(contains(raw, marker)).isFalse();
        assertThat(contains(raw, "Ionescu")).isFalse();
        assertThat(Sha256.hex(Sha256.of(raw))).isEqualTo(file.get("sha256").asText());
        if (type == StorageType.DB) { // direct dump of FileGridStorage.blob_chunks
            var template = new NamedParameterJdbcTemplate(storageDb);
            List<byte[]> chunks = template.query("SELECT c.chunk_data FROM dbo.blob_chunks c JOIN dbo.blob_versions v"
                    + " ON v.version_key = c.storage_key WHERE v.logical_key = :key ORDER BY c.chunk_offset",
                    new MapSqlParameterSource("key", storageKey), (r, n) -> r.getBytes(1));
            ByteArrayOutputStream dump = new ByteArrayOutputStream();
            chunks.forEach(c -> dump.writeBytes(c));
            assertThat(dump.size()).isEqualTo(raw.length);
            assertThat(contains(dump.toByteArray(), marker)).isFalse();
            assertThat(contains(dump.toByteArray(), "Ionescu")).isFalse();
        }

        // --- read back with an identical plain hash, no Range ---------------------------------------------------
        Resp download = send(base, "GET", "/api/v1/files/" + id + "/download", bearer.get(CONN), "bytes=0-9", null);
        assertThat(download.status()).isEqualTo(200);
        assertThat(download.headers().firstValue("Accept-Ranges")).isEmpty();
        assertThat(download.headers().firstValue("X-Content-SHA256")).contains(plainHash);
        assertThat(Sha256.hex(Sha256.of(download.body()))).isEqualTo(plainHash);
        assertThat(download.body()).isEqualTo(content);
        assertThat(send(base, "GET", "/api/v1/files/" + id + "/download", bearer.get(ADMIN), null, null).body())
                .isEqualTo(content);

        Resp shallow = send(base, "GET", "/api/v1/files/" + id + "/verify", bearer.get(CONN), null, null);
        assertThat(shallow.status()).isEqualTo(200);
        assertThat(shallow.json().get("ok").asBoolean()).isTrue();
        Resp deep = send(base, "GET", "/api/v1/files/" + id + "/verify?deep=true", bearer.get(CONN), null, null);
        assertThat(deep.status()).isEqualTo(200);
        assertThat(deep.json().get("deep").asBoolean()).isTrue();
        assertThat(deep.json().get("actualSha256Plain").asText()).isEqualTo(plainHash);

        Resp link = send(base, "POST", "/api/v1/files/" + id + "/download-link", bearer.get(CONN), null, null);
        assertThat(link.status()).isEqualTo(200);
        Resp byLink = send(base, "GET", link.json().get("url").asText(), null, null, null);
        assertThat(byLink.status()).isEqualTo(200);
        assertThat(byLink.body()).isEqualTo(content);

        // --- search: decrypted name only for authorized roles ---------------------------------------------------
        for (String user : new String[] {CONN, ADMIN}) {
            JsonNode items = send(base, "GET", "/api/v1/files?category=CONNECTION_DOC&size=100", bearer.get(user), null, null)
                    .json().get("items");
            boolean found = false;
            for (JsonNode item : items) {
                if (item.get("id").asText().equals(id)) {
                    found = true;
                    assertThat(item.get("originalName").asText()).isEqualTo(fileName);
                }
            }
            assertThat(found).as(user + " vede documentul").isTrue();
        }
        for (String user : new String[] {DISP, FIELD}) {
            assertThat(send(base, "GET", "/api/v1/files?category=CONNECTION_DOC", bearer.get(user), null, null).status())
                    .as(user).isEqualTo(403);
            JsonNode items = send(base, "GET", "/api/v1/files?size=100", bearer.get(user), null, null).json().get("items");
            for (JsonNode item : items) {
                assertThat(item.get("category").asText()).isNotEqualTo("CONNECTION_DOC");
            }
        }
        assertThat(send(base, "GET", "/api/v1/files", bearer.get(METER), null, null).status()).isEqualTo(403);

        // --- denials on an existing document: 403, no content leaked --------------------------------------------
        for (String user : new String[] {DISP, FIELD}) {
            for (String path : new String[] {"/api/v1/files/" + id, "/api/v1/files/" + id + "/download",
                    "/api/v1/files/" + id + "/verify"}) {
                assertThat(send(base, "GET", path, bearer.get(user), null, null).status()).as(user + path).isEqualTo(403);
            }
            assertThat(send(base, "POST", "/api/v1/files/" + id + "/download-link", bearer.get(user), null, null).status())
                    .isEqualTo(403);
        }
        assertThat(send(base, "GET", "/api/v1/files/" + id + "/download", null, null, null).status()).isEqualTo(401);
        assertThat(counter(metrics, "denied")).isGreaterThan(deniedBefore);
        assertThat(counter(metrics, "ok")).isGreaterThanOrEqualTo(7);

        // --- audit ------------------------------------------------------------------------------------------------
        Map<String, List<String>> actions = new HashMap<>();
        meta.query("SELECT actor, action FROM dbo.audit_log WHERE actor LIKE :p",
                new MapSqlParameterSource("p", "it-c10-%-" + RUN).addValue("l", "link:%"), r -> {
                    actions.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(r.getString(2));
                });
        assertThat(actions.get(CONN)).contains("UPLOAD", "DOWNLOAD", "VERIFY", "DOWNLOAD_LINK");
        assertThat(actions.get(ADMIN)).contains("DOWNLOAD");
        List<String> linkActors = meta.queryForList("SELECT actor FROM dbo.audit_log WHERE action = N'DOWNLOAD' AND actor LIKE N'link:%'"
                + " AND file_id = CAST(:id AS UNIQUEIDENTIFIER)", new MapSqlParameterSource("id", id), String.class);
        assertThat(linkActors).hasSize(1).allMatch(a -> !a.contains(CONN));
        assertThat(actions.get(DISP)).contains("DOWNLOAD_DENIED", "VERIFY_DENIED", "DOWNLOAD_LINK_DENIED", "SEARCH_DENIED");
        assertThat(actions.get(FIELD)).contains("UPLOAD_DENIED", "DOWNLOAD_DENIED");
        assertThat(app.getBean(AuditLog.class).verifyChain()).isGreaterThanOrEqualTo(15);

        // --- tampered segment in storage -> 409 FILE_CORRUPT -------------------------------------------------------
        raw[raw.length / 2] ^= 1;
        storage.put(storageKey, new java.io.ByteArrayInputStream(raw), raw.length, "application/octet-stream");
        Resp corruptVerify = send(base, "GET", "/api/v1/files/" + id + "/verify", bearer.get(CONN), null, null);
        assertThat(corruptVerify.status()).isEqualTo(409);
        assertThat(corruptVerify.json().get("code").asText()).isEqualTo("FILE_CORRUPT");
        // stored hash "fixed up" (like an attacker with database access): only GCM can still catch the tampering
        meta.update("UPDATE dbo.files SET sha256 = :h WHERE id = CAST(:id AS UNIQUEIDENTIFIER)",
                new MapSqlParameterSource("h", Sha256.of(raw)).addValue("id", id));
        assertThat(send(base, "GET", "/api/v1/files/" + id + "/verify", bearer.get(CONN), null, null).status())
                .isEqualTo(200);
        Resp corruptDeep = send(base, "GET", "/api/v1/files/" + id + "/verify?deep=true", bearer.get(CONN), null, null);
        assertThat(corruptDeep.status()).isEqualTo(409);
        assertThat(corruptDeep.json().get("code").asText()).isEqualTo("FILE_CORRUPT");
        assertThat(counter(metrics, "ok")).isPositive();
    }

    private void cleanUp(ConfigurableApplicationContext app, StorageType type, NamedParameterJdbcTemplate meta,
                         HikariDataSource storageDb, List<String> ids) {
        StorageAdapter storage = app.getBean(StorageAdapterRegistry.class).adapter(type);
        for (String id : ids) {
            try {
                String key = meta.queryForObject("SELECT storage_key FROM dbo.files WHERE id = CAST(:id AS UNIQUEIDENTIFIER)",
                        new MapSqlParameterSource("id", id), String.class);
                storage.delete(key);
                if (type == StorageType.DB) {
                    var template = new NamedParameterJdbcTemplate(storageDb);
                    var p = new MapSqlParameterSource("key", key);
                    template.update("DELETE c FROM dbo.blob_chunks c WHERE c.storage_key IN"
                            + " (SELECT version_key FROM dbo.blob_versions WHERE logical_key = :key)", p);
                    template.update("DELETE FROM dbo.blob_versions WHERE logical_key = :key", p);
                }
            } catch (RuntimeException e) {
                // best-effort cleanup: the object may already be gone
            }
        }
        var users = new MapSqlParameterSource("p", "it-c10-%"); // and leftovers from interrupted runs
        meta.update("DELETE FROM dbo.audit_log WHERE actor LIKE :p OR file_id IN (SELECT id FROM dbo.files"
                + " WHERE created_by LIKE :p)", users);
        meta.update("DELETE FROM dbo.files WHERE created_by LIKE :p", users);
    }

    // --- HTTP ---------------------------------------------------------------------------------------------------

    private record Resp(int status, java.net.http.HttpHeaders headers, byte[] body) {
        JsonNode json() throws IOException {
            return JSON.readTree(body);
        }
    }

    private Resp send(String base, String method, String path, String token, String range, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (range != null) {
            b.header("Range", range);
        }
        HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(r.statusCode(), r.headers(), r.body());
    }

    private Resp upload(String base, String token, String category, String name, byte[] content) throws Exception {
        String boundary = "----c10" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"category\"\r\n\r\n" + category
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/files"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        HttpResponse<byte[]> r = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(r.statusCode(), r.headers(), r.body());
    }

    private static double counter(MeterRegistry metrics, String result) {
        return metrics.counter("filegrid.doc.access", "result", result).count();
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void copyPolicies(Path dir, StorageType primary) throws IOException {
        StorageType fallback = primary == StorageType.S3 ? StorageType.DB : StorageType.S3;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/policies"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                String yaml = Files.readString(file, StandardCharsets.UTF_8);
                if (yaml.contains("category: CONNECTION_DOC")) {
                    yaml = yaml.replace("primaryStorage: DB", "primaryStorage: " + primary)
                            .replace("fallbackStorage: S3", "fallbackStorage: " + fallback);
                }
                Files.writeString(dir.resolve(file.getFileName()), yaml, StandardCharsets.UTF_8);
            }
        }
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }

    /** Environment variables, completed from the repo-root {@code .env} (the environment wins). */
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
                    // Without a readable .env, only the environment variables remain.
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
