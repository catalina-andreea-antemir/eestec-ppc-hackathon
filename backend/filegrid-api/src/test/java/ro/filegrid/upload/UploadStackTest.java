package ro.filegrid.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.ingest.FileMetadataRepository;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.ingest.StorageSelector;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.policy.PolicyEngine;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.ChunkReceipt;
import ro.filegrid.storage.ChunkedUpload;
import ro.filegrid.storage.ChunkedWriter;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageAdapterRegistry;
import ro.filegrid.storage.StorageHealth;
import ro.filegrid.storage.StorageUnavailableException;
import ro.filegrid.storage.db.DbStorageAdapter;
import ro.filegrid.storage.ftp.FtpAdapter;
import ro.filegrid.storage.ftp.FtpProperties;
import ro.filegrid.storage.s3.S3Adapter;
import ro.filegrid.storage.s3.S3Properties;
import ro.filegrid.upload.UploadService.Caller;
import ro.filegrid.upload.UploadService.UploadSettings;

/**
 * Chunked upload on the real stack, on every storage (S3/MinIO, FTP, DB): 1.1 GB with an identical hash (suite run
 * with {@code -Xmx256m}, see {@code argLine} in the pom), resume midway on another instance, two concurrent PUTs,
 * storage stopped during the upload, abort and cleanup. The services are the real ones, wired directly (no HTTP).
 * Data is generated as a stream (never committed, never held in memory). Without the stack (passwords, SQL Server,
 * MinIO, FTP) the tests are <b>skipped</b>.
 */
class UploadStackTest {

    private static final String JDBC = "jdbc:sqlserver://127.0.0.1:1433;encrypt=true;trustServerCertificate=true;"
            + "loginTimeout=3;databaseName=";
    private static final String USER = "it-c7b-" + UUID.randomUUID().toString().substring(0, 8);
    private static final Caller CALLER = new Caller(USER, false);
    private static final int MIB = 1024 * 1024;
    private static final long VIDEO_SIZE = 1_181_116_006L;

    private static HikariDataSource meta;
    private static HikariDataSource storageDb;
    private static S3Adapter s3;
    private static DbStorageAdapter db;
    private static FtpAdapter ftp;
    private static NamedParameterJdbcTemplate jdbc;

    private final Switch s3Switch = new Switch(s3);
    private final Switch ftpSwitch = new Switch(ftp);
    private final Switch dbSwitch = new Switch(db);

    @TempDir
    Path temp;

    @BeforeAll
    static void connect() {
        Map<String, String> env = environment();
        Assumptions.assumeTrue(env.containsKey("META_DB_PASSWORD") && env.containsKey("STORAGE_DB_PASSWORD")
                        && env.containsKey("S3_ACCESS_KEY") && env.containsKey("S3_SECRET_KEY")
                        && env.containsKey("FTP_USER") && env.containsKey("FTP_PASSWORD"),
                "Lipsesc parolele stack-ului (mediu sau .env); test sarit");
        S3Properties props = S3Properties.of(env.getOrDefault("S3_TEST_ENDPOINT", "http://127.0.0.1:9000"),
                "us-east-1", env.getOrDefault("MINIO_BUCKET", "filegrid"), env.get("S3_ACCESS_KEY"),
                env.get("S3_SECRET_KEY"));
        s3 = new S3Adapter(S3Adapter.buildClient(props), props.bucket(), 16L * MIB, Duration.ofSeconds(10),
                Duration.ofSeconds(2));
        ftp = FtpAdapter.fromProperties(FtpProperties.of(env.getOrDefault("FTP_TEST_HOST", "127.0.0.1"),
                Integer.parseInt(env.getOrDefault("FTP_TEST_PORT", "21")), env.get("FTP_USER"),
                env.get("FTP_PASSWORD")));
        try {
            meta = pool(JDBC + "FileGridMeta", "filegrid_meta_app", env.get("META_DB_PASSWORD"));
            storageDb = pool(JDBC + "FileGridStorage;responseBuffering=adaptive", "filegrid_storage_app",
                    env.get("STORAGE_DB_PASSWORD"));
            meta.getConnection().close();
            storageDb.getConnection().close();
        } catch (Exception e) {
            disconnect();
            Assumptions.abort("SQL Server indisponibil pe 127.0.0.1:1433 (" + e.getMessage() + "); test sarit");
        }
        db = new DbStorageAdapter(storageDb);
        if (!s3.health().up() || !db.health().up() || !ftp.health().up()) {
            disconnect();
            Assumptions.abort("MinIO, FTP sau FileGridStorage indisponibil; test sarit");
        }
        jdbc = new NamedParameterJdbcTemplate(meta);
    }

    @AfterAll
    static void disconnect() {
        if (s3 != null) {
            s3.close();
        }
        if (meta != null) {
            meta.close();
        }
        if (storageDb != null) {
            storageDb.close();
        }
    }

    @AfterEach
    void cleanUp() {
        var user = new MapSqlParameterSource("user", USER);
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT storage_type, CONVERT(NVARCHAR(36), upload_id) AS id, storage_upload_ref, chunk_size,"
                        + " total_size FROM dbo.upload_sessions WHERE created_by = :user", user)) {
            StorageAdapter storage = adapter((String) row.get("storage_type"));
            // SQL Server returns the GUID in upper case; the storage key is UUID.toString() (lower case).
            String key = UUID.fromString((String) row.get("id")).toString();
            // Unfinished sessions (S3 multipart, FTP / DB chunks) are deleted with abort; final objects with delete.
            try {
                ((ChunkedWriter) storage).abort(new ChunkedUpload(key, (String) row.get("storage_upload_ref"),
                        ((Number) row.get("total_size")).longValue(), ((Number) row.get("chunk_size")).intValue()));
            } catch (RuntimeException e) {
                System.err.println("[C7b] abort la curățare eșuat pentru " + key + ": " + e.getMessage());
            }
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                System.err.println("[C7b] delete la curățare eșuat pentru " + key + ": " + e.getMessage());
            }
            if (storage == db) {
                purgeDb(key);
            }
        }
        jdbc.update("DELETE c FROM dbo.upload_chunks c JOIN dbo.upload_sessions s ON s.upload_id = c.upload_id"
                + " WHERE s.created_by = :user", user);
        jdbc.update("DELETE f FROM dbo.files f JOIN dbo.upload_sessions s ON s.upload_id = f.id"
                + " WHERE s.created_by = :user", user);
        jdbc.update("DELETE FROM dbo.upload_sessions WHERE created_by = :user", user);
    }

    // --- 1.1 GB, identical hash, resume midway on another instance --------------------------------------------

    @ParameterizedTest
    @EnumSource(StorageType.class)
    void video11GbHasIdenticalHashAndResumesHalfwayOnAnotherInstance(StorageType type) throws Exception {
        Rig first = rig(type, 16 * MIB);
        VirtualFile video = new VirtualFile(VIDEO_SIZE, 7);
        UploadStatus created = first.service.init(Category.INSPECTION_VIDEO, "drona-tronson-7.mp4", VIDEO_SIZE, CALLER);
        assertThat(created.storage()).isEqualTo(type);
        assertThat(created.chunkSize()).isEqualTo(16 * MIB);
        assertThat(created.totalChunks()).isEqualTo(71);
        UUID id = created.uploadId();
        long maxHeapMb = Runtime.getRuntime().maxMemory() / MIB;
        System.out.println("[C7b " + type + "] heap maxim al JVM-ului de test: " + maxHeapMb + " MiB");

        for (int n = 1; n <= 35; n++) {
            first.put(id, n, video);
        }
        // "Pausing" the upload: the client comes back later, to another instance (another service, same databases).
        Rig second = rig(type, 16 * MIB);
        UploadStatus resumed = second.service.get(id, CALLER);
        assertThat(resumed.status()).isEqualTo("IN_PROGRESS");
        assertThat(resumed.nextChunk()).isEqualTo(36);
        assertThat(resumed.receivedBytes()).isEqualTo(35L * 16 * MIB);
        for (int n = resumed.nextChunk(); n <= 71; n++) {
            second.put(id, n, video);
        }

        UploadService.Completed done = second.service.complete(id, CALLER);

        assertThat(done.created()).isTrue();
        assertThat(done.file().size()).isEqualTo(VIDEO_SIZE);
        assertThat(done.file().status()).isEqualTo(FileRecord.CONFIRMED);
        assertThat(done.file().storage()).isEqualTo(type);
        assertThat(Sha256.hex(done.file().sha256())).isEqualTo(Sha256.hex(video.sha256()));
        assertThat(second.service.get(id, CALLER).status()).isEqualTo("COMPLETED");
        assertThat(hashInStorage(adapter(type.name()), id.toString())).isEqualTo(video.sha256());
        UploadService.Completed again = first.service.complete(id, CALLER);
        assertThat(again.created()).isFalse();
        assertThat(again.file().id()).isEqualTo(id);
        assertThat(again.file().sha256()).isEqualTo(done.file().sha256());
    }

    // --- protocol: ordering, duplicate, length, incomplete, abort ----------------------------------------------

    @ParameterizedTest
    @EnumSource(StorageType.class)
    void protocolRulesAndResumeThroughGet(StorageType type) throws Exception {
        Rig rig = rig(type, 5 * MIB);
        long size = 2L * 5 * MIB + 1_234_567;
        VirtualFile video = new VirtualFile(size, 11);
        UUID id = rig.service.init(Category.INSPECTION_VIDEO, "x.mp4", size, CALLER).uploadId();

        assertApi(() -> rig.put(id, 3, video), 409, "CHUNK_OUT_OF_ORDER");
        assertApi(() -> rig.service.putChunk(id, 1, CALLER, video.range(0, 100), 100), 400, "VALIDATION_ERROR");
        assertApi(() -> rig.service.putChunk(id, 1, CALLER, video.range(0, 5 * MIB - 10), -1), 400,
                "VALIDATION_ERROR");
        assertApi(() -> rig.service.putChunk(id, 9, CALLER, video.range(0, 10), 10), 400, "VALIDATION_ERROR");
        ChunkAck one = rig.put(id, 1, video);
        assertThat(one.duplicate()).isFalse();
        assertThat(one.nextChunk()).isEqualTo(2);
        assertThat(one.sha256()).isEqualTo(Sha256.hex(video.sha256(0, 5 * MIB)));
        ChunkAck repeat = rig.put(id, 1, video);
        assertThat(repeat.duplicate()).isTrue();
        assertThat(repeat.sha256()).isEqualTo(one.sha256());
        assertThat(repeat.nextChunk()).isEqualTo(2);
        assertApi(() -> rig.service.complete(id, CALLER), 409, "UPLOAD_INCOMPLETE");
        assertApi(() -> rig.service.get(id, new Caller("altcineva", false)), 403, "FORBIDDEN");
        assertThat(rig.service.get(id, new Caller("admin-it", true)).nextChunk()).isEqualTo(2);
        assertApi(() -> rig.service.get(UUID.randomUUID(), CALLER), 404, "NOT_FOUND");

        UploadStatus state = rig(type, 5 * MIB).service.get(id, CALLER);
        assertThat(state.nextChunk()).isEqualTo(2);
        assertThat(state.receivedBytes()).isEqualTo(5L * MIB);
        rig.put(id, state.nextChunk(), video);
        rig.put(id, 3, video);
        UploadService.Completed done = rig.service.complete(id, CALLER);

        assertThat(done.created()).isTrue();
        assertThat(done.file().sha256()).isEqualTo(video.sha256());
        assertThat(hashInStorage(adapter(type.name()), id.toString())).isEqualTo(video.sha256());
        assertThat(rig.service.complete(id, CALLER).created()).isFalse();
        assertApi(() -> rig.put(id, 3, video), 409, "UPLOAD_NOT_ACTIVE");
        assertApi(() -> rig.service.abort(id, CALLER), 409, "UPLOAD_NOT_ACTIVE");
    }

    @ParameterizedTest
    @EnumSource(StorageType.class)
    void abortCleansStorageAndIsIdempotent(StorageType type) throws Exception {
        Rig rig = rig(type, 5 * MIB);
        long size = 2L * 5 * MIB;
        VirtualFile video = new VirtualFile(size, 13);
        UUID id = rig.service.init(Category.INSPECTION_VIDEO, "x.mp4", size, CALLER).uploadId();
        rig.put(id, 1, video);

        assertThat(rig.service.abort(id, CALLER).status()).isEqualTo("ABORTED");
        assertThat(rig.service.abort(id, CALLER).status()).isEqualTo("ABORTED");

        assertApi(() -> rig.put(id, 2, video), 409, "UPLOAD_NOT_ACTIVE");
        assertApi(() -> rig.service.complete(id, CALLER), 409, "UPLOAD_NOT_ACTIVE");
        assertThat(adapter(type.name()).exists(id.toString())).isFalse();
    }

    // --- two concurrent PUTs on the same chunk -----------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(StorageType.class)
    void twoSimultaneousPutsOnTheSameChunkGiveOneOkAndOneBusy(StorageType type) throws Exception {
        Rig rig = rig(type, 5 * MIB);
        Rig other = rig(type, 5 * MIB);
        long size = 2L * 5 * MIB;
        VirtualFile video = new VirtualFile(size, 17);
        UUID id = rig.service.init(Category.INSPECTION_VIDEO, "x.mp4", size, CALLER).uploadId();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InputStream slow = new GatedStream(video.range(0, 5 * MIB), started, release);

        CompletableFuture<ChunkAck> a = CompletableFuture.supplyAsync(
                () -> rig.service.putChunk(id, 1, CALLER, slow, 5 * MIB));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        ApiException busy = catchApi(() -> other.put(id, 1, video));
        release.countDown();

        assertThat(busy.status().value()).isEqualTo(409);
        assertThat(busy.code()).isEqualTo("UPLOAD_BUSY");
        assertThat(busy.retryAfterSeconds()).isNotNull();
        ChunkAck ok = a.get(60, TimeUnit.SECONDS);
        assertThat(ok.duplicate()).isFalse();
        assertThat(ok.nextChunk()).isEqualTo(2);
        assertThat(other.put(id, 1, video).duplicate()).as("după ce lease-ul s-a eliberat").isTrue();
    }

    // --- storage stopped during the upload ---------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(StorageType.class)
    void storageDownGives503AndUploadResumesAfterRecovery(StorageType type) throws Exception {
        Rig rig = rig(type, 5 * MIB);
        long size = 2L * 5 * MIB + 77;
        VirtualFile video = new VirtualFile(size, 19);
        UUID id = rig.service.init(Category.INSPECTION_VIDEO, "x.mp4", size, CALLER).uploadId();
        rig.put(id, 1, video);

        rig.switchOf(type).up = false;
        ApiException down = catchApi(() -> rig.put(id, 2, video));
        assertThat(down.status().value()).isEqualTo(503);
        assertThat(down.code()).isEqualTo("STORAGE_UNAVAILABLE");
        assertThat(down.retryAfterSeconds()).isNotNull();
        assertThat(rig.service.get(id, CALLER).storage()).as("rămâne legată de storage-ul de la init").isEqualTo(type);
        assertThat(rig.service.get(id, CALLER).nextChunk()).isEqualTo(2);
        assertThat(downSince(id)).isTrue();
        UploadStatus other = rig.service.init(Category.INSPECTION_VIDEO, "nou.mp4", size, CALLER);
        assertThat(other.storage()).as("un upload nou alege rezerva").isNotEqualTo(type);
        rig.service.abort(other.uploadId(), CALLER);

        rig.switchOf(type).up = true;
        rig.expireHealthCache();
        rig.put(id, 2, video);
        rig.put(id, 3, video);
        assertThat(downSince(id)).as("căderea s-a încheiat").isFalse();
        assertThat(rig.service.complete(id, CALLER).file().sha256()).isEqualTo(video.sha256());
    }

    // --- abandon after 10 minutes and cleanup of sessions idle for 24 h; a single job at a time ---------------

    @ParameterizedTest
    @EnumSource(StorageType.class)
    void cleanupAbandonsSessionsDownTooLongAndInactiveOnes(StorageType type) throws Exception {
        Rig rig = rig(type, 5 * MIB);
        long size = 2L * 5 * MIB;
        VirtualFile video = new VirtualFile(size, 23);
        UUID down = rig.service.init(Category.INSPECTION_VIDEO, "a.mp4", size, CALLER).uploadId();
        UUID stale = rig.service.init(Category.INSPECTION_VIDEO, "b.mp4", size, CALLER).uploadId();
        UUID fresh = rig.service.init(Category.INSPECTION_VIDEO, "c.mp4", size, CALLER).uploadId();
        rig.put(down, 1, video);
        rig.put(stale, 1, video);
        rig.put(fresh, 1, video);
        rig.switchOf(type).up = false;
        catchApi(() -> rig.put(down, 2, video));
        rig.switchOf(type).up = true;
        rig.expireHealthCache();
        age(down, "storage_unavailable_since", 11 * 60);
        age(stale, "updated_at", 25 * 3600);
        age(fresh, "storage_unavailable_since", 5 * 60);

        UploadCleanupJob job = new UploadCleanupJob(rig.service, new org.springframework.jdbc.core.JdbcTemplate(meta));
        try (Connection other = meta.getConnection()) {
            assertThat(applock(other, true)).isTrue();
            assertThat(job.runExclusively()).as("altă instanță ține lock-ul").isEqualTo(-1);
            assertThat(applock(other, false)).isTrue();
        }
        assertThat(job.runExclusively()).isGreaterThanOrEqualTo(2);

        assertThat(rig.service.get(down, CALLER).status()).isEqualTo("ABANDONED");
        assertThat(rig.service.get(stale, CALLER).status()).isEqualTo("ABANDONED");
        assertThat(rig.service.get(fresh, CALLER).status()).as("sub pragul de 10 minute").isEqualTo("IN_PROGRESS");
        assertApi(() -> rig.put(down, 2, video), 409, "UPLOAD_NOT_ACTIVE");
        assertThat(adapter(type.name()).exists(down.toString())).isFalse();
    }

    // --- wiring ------------------------------------------------------------------------------------------------

    private Rig rig(StorageType primary, int chunkSize) throws IOException {
        return new Rig(primary, chunkSize);
    }

    /** One "api instance": its own policy, selector, registry and services, over the same databases and storage. */
    private final class Rig {
        final UploadService service;
        final StorageSelector selector;

        Rig(StorageType primary, int chunkSize) throws IOException {
            Path dir = Files.createTempDirectory(temp, "policies");
            copyPolicies(dir, primary, chunkSize);
            PolicyEngine policies = new PolicyEngine(dir.toString(), Duration.ofHours(1));
            List<Switch> all = List.of(s3Switch, ftpSwitch, dbSwitch);
            StorageAdapterRegistry registry = new StorageAdapterRegistry(new ArrayList<StorageAdapter>(all),
                    new ArrayList<ChunkedWriter>(all));
            selector = new StorageSelector(policies, registry);
            service = new UploadService(policies, selector, registry, new UploadSessionRepository(jdbc),
                    new FileMetadataRepository(jdbc), new TransactionTemplate(new DataSourceTransactionManager(meta)),
                    new UploadSettings(Duration.ofMinutes(2), Duration.ofHours(24), Duration.ofMinutes(10)),
                    Clock.systemUTC());
        }

        ChunkAck put(UUID id, int n, VirtualFile file) {
            UploadStatus s = service.get(id, CALLER);
            long offset = (long) (n - 1) * s.chunkSize();
            long length = Math.min(s.chunkSize(), s.totalSize() - offset);
            return service.putChunk(id, n, CALLER, file.range(offset, length), length);
        }

        Switch switchOf(StorageType type) {
            return switch (type) {
                case S3 -> s3Switch;
                case FTP -> ftpSwitch;
                case DB -> dbSwitch;
            };
        }

        void expireHealthCache() throws InterruptedException {
            Thread.sleep(2100); // StorageSelector caches health for 2 s
        }
    }

    private static void copyPolicies(Path dir, StorageType primary, int chunkSize) throws IOException {
        StorageType fallback = primary == StorageType.S3 ? StorageType.DB : StorageType.S3;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/policies"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                String yaml = Files.readString(file, StandardCharsets.UTF_8);
                if (yaml.contains("category: INSPECTION_VIDEO")) {
                    yaml = yaml.replace("primaryStorage: FTP", "primaryStorage: " + primary)
                            .replace("fallbackStorage: S3", "fallbackStorage: " + fallback)
                            .replace("chunkSize: 16MiB", "chunkSize: " + (chunkSize / MIB) + "MiB");
                }
                Files.writeString(dir.resolve(file.getFileName()), yaml, StandardCharsets.UTF_8);
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    /**
     * Safety net for large DB objects (1.1 GB = hundreds of rows, deleting them in one transaction exceeds the adapter
     * timeout): deletes everything tied to the test key, in batches, directly in FileGridStorage.
     */
    private static void purgeDb(String key) {
        var template = new NamedParameterJdbcTemplate(storageDb);
        var p = new MapSqlParameterSource("key", key);
        int deleted;
        do {
            deleted = template.update("DELETE TOP (10) c FROM dbo.blob_chunks c WHERE c.storage_key IN"
                    + " (SELECT version_key FROM dbo.blob_versions WHERE logical_key = :key)", p);
        } while (deleted > 0);
        template.update("DELETE FROM dbo.blob_versions WHERE logical_key = :key", p);
    }

    private static StorageAdapter adapter(String type) {
        return switch (type) {
            case "S3" -> s3;
            case "FTP" -> ftp;
            default -> db;
        };
    }

    private static byte[] hashInStorage(StorageAdapter adapter, String key) throws Exception {
        MessageDigest digest = Sha256.newDigest();
        try (InputStream in = adapter.get(key, ByteRange.full())) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static boolean downSince(UUID id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.upload_sessions WHERE storage_unavailable_since"
                + " IS NOT NULL AND upload_id = CAST(:id AS UNIQUEIDENTIFIER)",
                new MapSqlParameterSource("id", id.toString()), Integer.class);
        return n != null && n == 1;
    }

    /** Moves a session time column into the past (simulates inactivity / a long outage). */
    private static void age(UUID id, String column, int seconds) {
        jdbc.update("UPDATE dbo.upload_sessions SET " + column + " = DATEADD(SECOND, -:s, SYSUTCDATETIME())"
                + " WHERE upload_id = CAST(:id AS UNIQUEIDENTIFIER)",
                new MapSqlParameterSource("id", id.toString()).addValue("s", seconds));
    }

    private static boolean applock(Connection con, boolean acquire) throws Exception {
        String sql = acquire
                ? "{? = call sp_getapplock(@Resource = ?, @LockMode = 'Exclusive', @LockOwner = 'Session',"
                + " @LockTimeout = 0)}"
                : "{? = call sp_releaseapplock(@Resource = ?, @LockOwner = 'Session')}";
        try (CallableStatement call = con.prepareCall(sql)) {
            call.registerOutParameter(1, Types.INTEGER);
            call.setString(2, UploadCleanupJob.LOCK_RESOURCE);
            call.execute();
            return call.getInt(1) >= 0;
        }
    }

    private static ApiException catchApi(Runnable call) {
        ApiException[] caught = new ApiException[1];
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> caught[0] = e);
        return caught[0];
    }

    private static void assertApi(Runnable call, int status, String code) {
        ApiException e = catchApi(call);
        assertThat(e.status().value()).isEqualTo(status);
        assertThat(e.code()).isEqualTo(code);
    }

    private static HikariDataSource pool(String url, String user, String password) {
        HikariDataSource pool = DataSourceBuilder.create().type(HikariDataSource.class).url(url).username(user)
                .password(password).build();
        pool.setMaximumPoolSize(20);
        pool.setConnectionTimeout(3000);
        pool.setInitializationFailTimeout(-1);
        return pool;
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

    /** Deterministic virtual content, streamed (no files exist and nothing is held in memory). */
    private static final class VirtualFile {
        private static final int PERIOD = 1 << 16;
        private final long size;
        private final byte[] pattern = new byte[PERIOD];
        private byte[] cachedHash;

        VirtualFile(long size, long seed) {
            this.size = size;
            new Random(seed).nextBytes(pattern);
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

    /** A body that signals its first read and then waits for a signal (keeps the lease busy while the second PUT runs). */
    private static final class GatedStream extends InputStream {
        private final InputStream source;
        private final CountDownLatch started;
        private final CountDownLatch release;
        private boolean gated;

        GatedStream(InputStream source, CountDownLatch started, CountDownLatch release) {
            this.source = source;
            this.started = started;
            this.release = release;
        }

        @Override
        public int read() throws IOException {
            gate();
            return source.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            gate();
            return source.read(b, off, len);
        }

        private void gate() throws IOException {
            if (!gated) {
                gated = true;
                started.countDown();
                try {
                    if (!release.await(60, TimeUnit.SECONDS)) {
                        throw new IOException("semnal de eliberare lipsă");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
        }
    }

    /** A real storage that can be "switched off": health down and every operation -> unavailable. */
    private static final class Switch implements StorageAdapter, ChunkedWriter {
        private final StorageAdapter target;
        volatile boolean up = true;

        Switch(StorageAdapter target) {
            this.target = target;
        }

        @Override
        public StorageType type() {
            return target.type();
        }

        @Override
        public long put(String key, InputStream data, long length, String contentType) {
            return check().put(key, data, length, contentType);
        }

        @Override
        public InputStream get(String key, ByteRange range) {
            return check().get(key, range);
        }

        @Override
        public boolean delete(String key) {
            return check().delete(key);
        }

        @Override
        public boolean exists(String key) {
            return check().exists(key);
        }

        @Override
        public StorageHealth health() {
            return up ? target.health() : StorageHealth.down(type(), Duration.ZERO, "oprit în test");
        }

        @Override
        public String open(String key, long totalSize, int chunkSize) {
            return writer().open(key, totalSize, chunkSize);
        }

        @Override
        public ChunkReceipt append(ChunkedUpload upload, int n, InputStream data, long length) {
            return writer().append(upload, n, data, length);
        }

        @Override
        public long finish(ChunkedUpload upload, List<ChunkReceipt> chunks) {
            return writer().finish(upload, chunks);
        }

        @Override
        public void abort(ChunkedUpload upload) {
            writer().abort(upload);
        }

        private ChunkedWriter writer() {
            return (ChunkedWriter) check();
        }

        private StorageAdapter check() {
            if (!up) {
                throw new StorageUnavailableException(type(), "oprit în test");
            }
            return target;
        }
    }
}
