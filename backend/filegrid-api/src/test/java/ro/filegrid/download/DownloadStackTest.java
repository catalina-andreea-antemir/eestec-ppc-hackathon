package ro.filegrid.download;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.ingest.FileMetadataRepository;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.security.Role;
import ro.filegrid.security.TokenService;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageAdapterRegistry;
import ro.filegrid.upload.UploadService;
import ro.filegrid.upload.UploadStatus;

/**
 * C8 pe stack-ul real, pe fiecare storage (S3/MinIO, FTP, DB): aplicația pornește cu profilul {@code db} (Tomcat real,
 * pe port aleatoriu), iar verificările merg prin HTTP. Pe storage: fișier mic corupt manual în storage (un octet) →
 * {@code /verify} și {@code /download} 409; fișier de 20 MiB corupt → conexiunea se întrerupe înainte de ultimul
 * bloc; 1,1 GB urcat prin {@code /uploads} (bucăți de 16 MiB): Range exact, inclusiv peste granița dintre bucăți,
 * descărcare întreagă cu hash identic sub {@code -Xmx256m}, {@code /verify}, descărcare întreruptă și reluată cu Range
 * prin link semnat; căutare cu paginare pe baza reală. Datele se șterg la final. Fără stack, testele se <b>sar</b>.
 */
class DownloadStackTest {

    private static final String JDBC = "jdbc:sqlserver://127.0.0.1:1433;encrypt=true;trustServerCertificate=true;"
            + "loginTimeout=3;databaseName=";
    private static final String USER = "it-c8-" + UUID.randomUUID().toString().substring(0, 8);
    private static final UploadService.Caller CALLER = new UploadService.Caller(USER, false);
    private static final int MIB = 1024 * 1024;
    private static final long VIDEO_SIZE = 1_181_116_006L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @ParameterizedTest
    @EnumSource(StorageType.class)
    void downloadVerifyAndRangeOnEachStorage(StorageType type) throws Exception {
        Map<String, String> env = environment();
        Assumptions.assumeTrue(env.containsKey("META_DB_PASSWORD") && env.containsKey("STORAGE_DB_PASSWORD")
                        && env.containsKey("S3_ACCESS_KEY") && env.containsKey("S3_SECRET_KEY")
                        && env.containsKey("FTP_USER") && env.containsKey("FTP_PASSWORD"),
                "Lipsesc parolele stack-ului (mediu sau .env); test sarit");
        for (int port : new int[]{1433, 9000, 21}) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(500);
            } catch (IOException e) {
                Assumptions.abort("Stack-ul nu răspunde pe 127.0.0.1:" + port + "; test sarit");
            }
        }
        Path policies = Files.createDirectory(temp.resolve("policies"));
        copyPolicies(policies, type);

        List<String> adminAndSecrets = new ArrayList<>(List.of(
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
                "--filegrid.encryption.key=" + Base64.getEncoder().encodeToString(random(32)),
                "--filegrid.security.jwt.secret=" + Base64.getEncoder().encodeToString(random(48)),
                "--filegrid.download.link-secret=" + Base64.getEncoder().encodeToString(random(48)),
                "--spring.main.banner-mode=off", "--logging.level.root=WARN"));
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(FileGridApplication.class)
                .run(adminAndSecrets.toArray(new String[0]))) {
            Run run = new Run(app, type, env);
            currentBase = run.base;
            currentBearer = run.bearer;
            try {
                run.smallFilesAndCorruption();
                run.largeCorruptionBreaksTheConnection();
                if (type == StorageType.S3) {
                    run.searchAndPagination();
                }
                run.video();
            } finally {
                run.cleanUp();
            }
        }
    }

    /** O rulare pe un storage: aplicația pornită, tokenul admin și lista a ce trebuie curățat. */
    private final class Run {
        final ConfigurableApplicationContext app;
        final StorageType type;
        final String base;
        final String bearer;
        final StorageAdapter storage;
        final FileMetadataRepository files;
        final NamedParameterJdbcTemplate meta;
        final UploadService uploads;
        final HikariDataSource storageDb;
        final List<UUID> keys = new ArrayList<>();

        Run(ConfigurableApplicationContext app, StorageType type, Map<String, String> env) {
            this.app = app;
            this.type = type;
            this.base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
            this.bearer = "Bearer " + app.getBean(TokenService.class).issueAccessToken("admin", Role.ADMIN);
            this.storage = app.getBean(StorageAdapterRegistry.class).adapter(type);
            this.files = app.getBean(FileMetadataRepository.class);
            this.meta = app.getBean(NamedParameterJdbcTemplate.class);
            this.uploads = app.getBean(UploadService.class);
            this.storageDb = DataSourceBuilder.create().type(HikariDataSource.class)
                    .url(JDBC + "FileGridStorage;responseBuffering=adaptive").username("filegrid_storage_app")
                    .password(env.get("STORAGE_DB_PASSWORD")).build();
            storageDb.setMaximumPoolSize(2);
            storageDb.setInitializationFailTimeout(-1);
        }

        /** Pune un fișier în storage (cu un octet schimbat la {@code flipAt}, dacă e >= 0) și îi scrie rândul din files. */
        UUID register(String name, long size, long seed, long hashSeedFlip, boolean outage) throws Exception {
            UUID id = UUID.randomUUID();
            Virtual original = new Virtual(size, seed);
            keys.add(id);
            storage.put(id.toString(), original.range(0, size), size, "text/csv");
            files.insert(new FileRecord(id, outage ? Category.OUTAGE_RECORD : Category.INSPECTION_VIDEO, name,
                    "text/csv", size, original.sha256(), null, type, id.toString(), FileRecord.CONFIRMED, false,
                    Instant.now().truncatedTo(ChronoUnit.MICROS), USER,
                    outage ? new FileRecord.OutageInfo("Muntenia", "ST-IT", Instant.now().truncatedTo(ChronoUnit.MICROS))
                            : null));
            return id;
        }

        /** Strică manual un octet din storage, ca după o corupere pe disc / în bază. */
        void corrupt(UUID id, long size, long seed, long at) {
            storage.put(id.toString(), new Flipped(new Virtual(size, seed).range(0, size), at), size, "text/csv");
        }

        void smallFilesAndCorruption() throws Exception {
            long size = 300_000;
            UUID id = register("mic-" + type + ".csv", size, 11, -1, true);
            JsonNode ok = json(200, "/api/v1/files/" + id + "/verify");
            assertThat(ok.get("ok").asBoolean()).isTrue();
            assertThat(ok.get("actualSha256").asText()).isEqualTo(ok.get("expectedSha256").asText());
            Fetched good = fetch("/api/v1/files/" + id + "/download", null);
            assertThat(good.status).isEqualTo(200);
            assertThat(good.hash).isEqualTo(good.header("x-content-sha256"));
            assertThat(good.bytes).isEqualTo(size);
            Fetched range = fetch("/api/v1/files/" + id + "/download", "bytes=100-199");
            assertThat(range.status).isEqualTo(206);
            assertThat(range.header("content-range")).isEqualTo("bytes 100-199/" + size);
            assertThat(range.hash).isEqualTo(Virtual.hex(new Virtual(size, 11).sha256(100, 100)));
            assertThat(fetch("/api/v1/files/" + id + "/download", "bytes=" + size + "-").status).isEqualTo(416);

            corrupt(id, size, 11, 150_000);
            JsonNode verify = json(409, "/api/v1/files/" + id + "/verify");
            assertThat(verify.get("code").asText()).isEqualTo("FILE_CORRUPT");
            assertThat(verify.get("message").asText()).isEqualTo("fișier corupt");
            assertThat(verify.get("details").get("expectedSha256").asText())
                    .isNotEqualTo(verify.get("details").get("actualSha256").asText());
            assertThat(verify.get("traceId").asText()).isNotBlank();
            Fetched download = fetch("/api/v1/files/" + id + "/download", null);
            assertThat(download.status).isEqualTo(409);
            assertThat(download.headers.firstValue("x-content-sha256")).isEmpty();
            assertThat(JSON.readTree(download.body).get("code").asText()).isEqualTo("FILE_CORRUPT");
        }

        void largeCorruptionBreaksTheConnection() throws Exception {
            long size = 20L * MIB; // peste pragul de 16 MB: hash-ul se verifică în timpul trimiterii
            UUID id = register("mediu-" + type + ".csv", size, 12, -1, true);
            Fetched good = fetch("/api/v1/files/" + id + "/download", null);
            assertThat(good.status).isEqualTo(200);
            assertThat(good.hash).isEqualTo(good.header("x-content-sha256"));
            assertThat(good.bytes).isEqualTo(size);

            corrupt(id, size, 12, size / 2);
            assertThat(json(409, "/api/v1/files/" + id + "/verify").get("code").asText()).isEqualTo("FILE_CORRUPT");
            HttpResponse<InputStream> response = http.send(request("/api/v1/files/" + id + "/download", null),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("x-content-sha256")).contains(Virtual.hex(new Virtual(size, 12).sha256()));
            long received = 0;
            boolean broken = false;
            try (InputStream in = response.body()) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    received += n;
                }
            } catch (IOException e) {
                broken = true;
            }
            // Conexiunea s-a întrerupt: fie eroare de citire, fie mai puțini octeți decât Content-Length.
            assertThat(broken || received < size).as("descărcarea coruptă nu trebuie să se încheie normal").isTrue();
        }

        void searchAndPagination() throws Exception {
            String tag = USER.replace("it-c8-", "q");
            List<UUID> ids = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                UUID id = register("cautare-" + tag + "-" + i + "_%.csv", 2000 + i, 20 + i, -1, true);
                ids.add(id);
                meta.update("UPDATE dbo.files SET created_at = :t WHERE id = CAST(:id AS UNIQUEIDENTIFIER)",
                        new MapSqlParameterSource().addValue("t", java.time.LocalDateTime.of(2020, 1, i, 12, 0))
                                .addValue("id", id.toString()));
            }
            String q = "category=OUTAGE_RECORD&q=" + tag;
            JsonNode page1 = json(200, "/api/v1/files?" + q + "&size=2");
            assertThat(page1.get("totalItems").asInt()).isEqualTo(3);
            assertThat(page1.get("totalPages").asInt()).isEqualTo(2);
            assertThat(page1.get("page").asInt()).isEqualTo(1);
            assertThat(page1.get("items")).hasSize(2);
            assertThat(page1.get("items").get(0).get("id").asText()).isEqualTo(ids.get(2).toString()); // cele mai noi întâi
            assertThat(page1.get("items").get(0).get("outage").get("zone").asText()).isEqualTo("Muntenia");
            assertThat(page1.get("items").get(0).has("storageKey")).isFalse();
            JsonNode page2 = json(200, "/api/v1/files?" + q + "&size=2&page=2");
            assertThat(page2.get("items")).hasSize(1);
            assertThat(page2.get("items").get(0).get("id").asText()).isEqualTo(ids.get(0).toString());
            assertThat(json(200, "/api/v1/files?" + q + "&size=2&page=3").get("items")).isEmpty();
            // from inclusiv, to exclusiv
            assertThat(json(200, "/api/v1/files?" + q + "&from=2020-01-02T12:00:00Z&to=2020-01-03T12:00:00Z")
                    .get("items")).hasSize(1);
            // % și _ din nume nu sunt metacaractere; q fără potrivire
            assertThat(json(200, "/api/v1/files?q=" + tag + "-1_%25").get("items")).hasSize(1);
            assertThat(json(200, "/api/v1/files?q=" + tag + "-%25-").get("items")).isEmpty();
            assertThat(json(200, "/api/v1/files?category=INSPECTION_VIDEO&q=" + tag).get("items")).isEmpty();
            JsonNode one = json(200, "/api/v1/files/" + ids.get(1));
            assertThat(one.get("originalName").asText()).startsWith("cautare-" + tag);
            assertThat(one.get("storage").asText()).isEqualTo(type.name());
            json(404, "/api/v1/files/" + UUID.randomUUID());
        }

        void video() throws Exception {
            Virtual video = new Virtual(VIDEO_SIZE, 7);
            UploadStatus created = uploads.init(Category.INSPECTION_VIDEO, "drona-" + type + ".mp4", VIDEO_SIZE, CALLER);
            assertThat(created.storage()).isEqualTo(type);
            UUID id = created.uploadId();
            keys.add(id);
            for (int n = 1; n <= created.totalChunks(); n++) {
                long offset = (long) (n - 1) * created.chunkSize();
                long length = Math.min(created.chunkSize(), VIDEO_SIZE - offset);
                uploads.putChunk(id, n, CALLER, video.range(offset, length), length);
            }
            uploads.complete(id, CALLER);
            String expected = Virtual.hex(video.sha256());
            String file = "/api/v1/files/" + id;

            // Range exact, inclusiv peste granița dintre bucăți (16 MiB).
            long chunk = created.chunkSize();
            long[][] ranges = {{chunk - 5, 20}, {3 * chunk - 1, 2}, {chunk, chunk}, {5, 1}, {VIDEO_SIZE - 70_000, 70_000}};
            for (long[] r : ranges) {
                Fetched part = fetch(file + "/download", "bytes=" + r[0] + "-" + (r[0] + r[1] - 1));
                assertThat(part.status).as("%s %s", type, r[0]).isEqualTo(206);
                assertThat(part.header("content-range")).isEqualTo("bytes " + r[0] + "-" + (r[0] + r[1] - 1) + "/" + VIDEO_SIZE);
                assertThat(part.bytes).isEqualTo(r[1]);
                assertThat(part.hash).as("%s range %s+%s", type, r[0], r[1]).isEqualTo(Virtual.hex(video.sha256(r[0], r[1])));
                assertThat(part.header("x-content-sha256")).isEqualTo(expected);
            }
            Fetched suffix = fetch(file + "/download", "bytes=-1000");
            assertThat(suffix.hash).isEqualTo(Virtual.hex(video.sha256(VIDEO_SIZE - 1000, 1000)));
            assertThat(fetch(file + "/download", "bytes=" + VIDEO_SIZE + "-").status).isEqualTo(416);

            // /verify pe 1,1 GB
            JsonNode verified = json(200, file + "/verify");
            assertThat(verified.get("actualSha256").asText()).isEqualTo(expected);

            // Link semnat: descărcare întreagă (heap -Xmx256m), apoi întreruptă și reluată cu Range.
            HttpResponse<String> linkResponse = http.send(HttpRequest.newBuilder(URI.create(base + file + "/download-link"))
                    .header("Authorization", bearer).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(linkResponse.statusCode()).isEqualTo(200);
            String url = JSON.readTree(linkResponse.body()).get("url").asText();
            Fetched whole = fetchPublic(url, null, -1);
            assertThat(whole.status).isEqualTo(200);
            assertThat(whole.bytes).isEqualTo(VIDEO_SIZE);
            assertThat(whole.hash).isEqualTo(expected).isEqualTo(whole.header("x-content-sha256"));
            assertThat(whole.header("content-disposition")).startsWith("attachment; filename=\"drona-");

            long cut = 100L * MIB + 12_345;
            MessageDigest combined = Sha256.newDigest();
            Fetched first = fetchPublic(url, null, cut, combined);
            assertThat(first.bytes).isEqualTo(cut); // clientul a renunțat după `cut` octeți
            Fetched rest = fetchPublic(url, "bytes=" + cut + "-", -1, combined);
            assertThat(rest.status).isEqualTo(206);
            assertThat(rest.header("content-range")).isEqualTo("bytes " + cut + "-" + (VIDEO_SIZE - 1) + "/" + VIDEO_SIZE);
            assertThat(first.bytes + rest.bytes).isEqualTo(VIDEO_SIZE);
            assertThat(Virtual.hex(combined.digest())).isEqualTo(expected);
        }

        void cleanUp() {
            var user = new MapSqlParameterSource("user", USER);
            for (UUID key : keys) {
                try {
                    storage.delete(key.toString());
                } catch (RuntimeException e) {
                    System.err.println("[C8] delete la curățare eșuat pentru " + key + ": " + e.getMessage());
                }
                if (type == StorageType.DB) {
                    purgeDb(key.toString());
                }
            }
            try {
                meta.update("DELETE c FROM dbo.upload_chunks c JOIN dbo.upload_sessions s ON s.upload_id = c.upload_id"
                        + " WHERE s.created_by = :user", user);
                meta.update("DELETE o FROM dbo.outage_records o JOIN dbo.files f ON f.id = o.file_id"
                        + " WHERE f.created_by = :user", user);
                meta.update("DELETE f FROM dbo.files f JOIN dbo.upload_sessions s ON s.upload_id = f.id"
                        + " WHERE s.created_by = :user", user);
                meta.update("DELETE FROM dbo.files WHERE created_by = :user", user);
                meta.update("DELETE FROM dbo.upload_sessions WHERE created_by = :user", user);
            } finally {
                storageDb.close();
            }
        }

        void purgeDb(String key) {
            var template = new NamedParameterJdbcTemplate(storageDb);
            var p = new MapSqlParameterSource("key", key);
            int deleted;
            do {
                deleted = template.update("DELETE TOP (10) c FROM dbo.blob_chunks c WHERE c.storage_key IN"
                        + " (SELECT version_key FROM dbo.blob_versions WHERE logical_key = :key)", p);
            } while (deleted > 0);
            template.update("DELETE FROM dbo.blob_versions WHERE logical_key = :key", p);
        }
    }

    // --- HTTP -------------------------------------------------------------------------------------------------

    private HttpRequest request(String path, String range) {
        return requestTo(path, range, true);
    }

    private HttpRequest requestTo(String path, String range, boolean auth) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(currentBase + path)).GET();
        if (auth) {
            b.header("Authorization", currentBearer);
        }
        if (range != null) {
            b.header("Range", range);
        }
        return b.build();
    }

    private String currentBase;
    private String currentBearer;

    private Fetched fetchWith(String path, String range, boolean auth, long stopAfter, MessageDigest digest)
            throws Exception {
        HttpResponse<InputStream> r = http.send(requestTo(path, range, auth), HttpResponse.BodyHandlers.ofInputStream());
        MessageDigest d = digest != null ? digest : Sha256.newDigest();
        long total = 0;
        boolean json = r.headers().firstValue("content-type").orElse("").startsWith("application/json");
        java.io.ByteArrayOutputStream errorBody = json ? new java.io.ByteArrayOutputStream() : null;
        try (InputStream in = r.body()) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while (stopAfter < 0 || total < stopAfter) {
                int want = stopAfter < 0 ? buffer.length : (int) Math.min(buffer.length, stopAfter - total);
                n = in.read(buffer, 0, want);
                if (n < 0) {
                    break;
                }
                if (errorBody != null) {
                    errorBody.write(buffer, 0, n);
                } else {
                    d.update(buffer, 0, n);
                }
                total += n;
            }
        }
        return new Fetched(r.statusCode(), r.headers(), total, digest != null ? null : Virtual.hex(d.digest()),
                errorBody == null ? "" : errorBody.toString(StandardCharsets.UTF_8));
    }

    private record Fetched(int status, java.net.http.HttpHeaders headers, long bytes, String hash, String body) {
        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    private Fetched fetch(String path, String range) throws Exception {
        return fetchWith(path, range, true, -1, null);
    }

    private Fetched fetchPublic(String path, String range, long stopAfter) throws Exception {
        return fetchWith(path, range, false, stopAfter, null);
    }

    private Fetched fetchPublic(String path, String range, long stopAfter, MessageDigest digest) throws Exception {
        return fetchWith(path, range, false, stopAfter, digest);
    }

    private JsonNode json(int status, String path) throws Exception {
        HttpResponse<String> r = http.send(requestTo(path, null, true), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(status);
        return JSON.readTree(r.body());
    }

    // --- ajutoare ----------------------------------------------------------------------------------------------

    private static void copyPolicies(Path dir, StorageType primary) throws IOException {
        StorageType fallback = primary == StorageType.S3 ? StorageType.DB : StorageType.S3;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/policies"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                String yaml = Files.readString(file, StandardCharsets.UTF_8);
                if (yaml.contains("category: INSPECTION_VIDEO")) {
                    yaml = yaml.replace("primaryStorage: FTP", "primaryStorage: " + primary)
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

    /** Conținut virtual determinist, citit în streaming (nu există fișiere și nu se ține nimic în memorie). */
    private static final class Virtual {
        private static final int PERIOD = 1 << 16;
        private final long size;
        private final byte[] pattern = new byte[PERIOD];
        private byte[] cachedHash;

        Virtual(long size, long seed) {
            this.size = size;
            new Random(seed).nextBytes(pattern);
        }

        static String hex(byte[] hash) {
            return HexFormat.of().formatHex(hash);
        }

        InputStream range(long offset, long length) {
            return new InputStream() {
                private long position = offset;
                private final long end = offset + length;

                @Override
                public int read() {
                    return position >= end ? -1 : at(position++) & 0xff;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    if (position >= end) {
                        return -1;
                    }
                    int n = (int) Math.min(len, end - position);
                    for (int i = 0; i < n; i++) {
                        b[off + i] = at(position + i);
                    }
                    position += n;
                    return n;
                }
            };
        }

        private byte at(long p) {
            return (byte) (pattern[(int) (p & (PERIOD - 1))] ^ (byte) (p >>> 16) ^ (byte) (p >>> 29));
        }

        byte[] sha256() throws IOException {
            if (cachedHash == null) {
                cachedHash = sha256(0, size);
            }
            return cachedHash;
        }

        byte[] sha256(long offset, long length) throws IOException {
            MessageDigest digest = Sha256.newDigest();
            try (InputStream in = range(offset, length)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return digest.digest();
        }
    }

    /** Aceleași octeți, cu unul schimbat la poziția {@code at}. */
    private static final class Flipped extends InputStream {
        private final InputStream source;
        private final long at;
        private long position;

        Flipped(InputStream source, long at) {
            this.source = source;
            this.at = at;
        }

        @Override
        public int read() throws IOException {
            int b = source.read();
            return b >= 0 && position++ == at ? b ^ 0x01 : b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = source.read(buffer, off, len);
            if (n > 0 && at >= position && at < position + n) {
                buffer[off + (int) (at - position)] ^= 0x01;
            }
            if (n > 0) {
                position += n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
