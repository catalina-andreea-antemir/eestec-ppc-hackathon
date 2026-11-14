package ro.filegrid.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.policy.PolicyEngine;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageAdapterRegistry;
import ro.filegrid.storage.StorageHealth;
import ro.filegrid.storage.StorageUnavailableException;
import ro.filegrid.storage.db.DbStorageAdapter;
import ro.filegrid.storage.db.MeterReadingBatch;
import ro.filegrid.storage.db.MeterReadingRepository;
import ro.filegrid.storage.s3.S3Adapter;
import ro.filegrid.storage.s3.S3Properties;

/**
 * Ingestia pe stack-ul real (C7): SQL Server (FileGridMeta + FileGridStorage) și MinIO, prin porturile de dezvoltare
 * ({@code docker compose -f docker-compose.yml -f infra/docker/docker-compose.dev.yml up -d}: 1433 și 9000 pe
 * 127.0.0.1). Parolele vin din mediu sau din {@code .env} (nu se afișează). Fără stack, testele se <b>sar</b>.
 */
class IngestionStackTest {

    private static final String JDBC = "jdbc:sqlserver://127.0.0.1:1433;encrypt=true;trustServerCertificate=true;"
            + "loginTimeout=3;databaseName=";
    private static final String USER = "it-c7-" + UUID.randomUUID().toString().substring(0, 8);

    private static HikariDataSource meta;
    private static HikariDataSource storageDb;
    private static S3Adapter s3;
    private static DbStorageAdapter db;
    private static NamedParameterJdbcTemplate jdbc;

    private final Switchable s3Switch = new Switchable(s3);
    private final Switchable dbSwitch = new Switchable(db);
    private final List<String> meters = new ArrayList<>();
    private FileIngestionService files;
    private MeterReadingIngestionService readings;

    @TempDir
    Path temp;

    @BeforeAll
    static void connect() {
        Map<String, String> env = environment();
        Assumptions.assumeTrue(env.containsKey("META_DB_PASSWORD") && env.containsKey("STORAGE_DB_PASSWORD")
                && env.containsKey("S3_ACCESS_KEY") && env.containsKey("S3_SECRET_KEY"),
                "Lipsesc parolele stack-ului (mediu sau .env); test sarit");
        S3Properties props = S3Properties.of(env.getOrDefault("S3_TEST_ENDPOINT", "http://127.0.0.1:9000"),
                "us-east-1", env.getOrDefault("MINIO_BUCKET", "filegrid"), env.get("S3_ACCESS_KEY"),
                env.get("S3_SECRET_KEY"));
        s3 = new S3Adapter(S3Adapter.buildClient(props), props.bucket(), 16L * 1024 * 1024, Duration.ofSeconds(10),
                Duration.ofSeconds(2));
        try {
            meta = pool(JDBC + "FileGridMeta", "filegrid_meta_app", env.get("META_DB_PASSWORD"));
            storageDb = pool(JDBC + "FileGridStorage;responseBuffering=adaptive", "filegrid_storage_app",
                    env.get("STORAGE_DB_PASSWORD"));
            meta.getConnection().close();
            storageDb.getConnection().close();
        } catch (Exception e) {
            disconnect();
            Assumptions.abort("SQL Server indisponibil pe 127.0.0.1:1433 (" + e.getMessage()
                    + "); test sarit. Porneste stack-ul cu docker-compose.dev.yml.");
        }
        db = new DbStorageAdapter(storageDb);
        if (!s3.health().up() || !db.health().up()) {
            disconnect();
            Assumptions.abort("MinIO sau FileGridStorage indisponibil; test sarit");
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

    @org.junit.jupiter.api.BeforeEach
    void wire() {
        PolicyEngine policies = new PolicyEngine("classpath:/policies/", Duration.ofHours(1));
        StorageAdapterRegistry registry = new StorageAdapterRegistry(List.of(s3Switch, dbSwitch), List.of());
        ContentWriter writer = new ContentWriter(new StorageSelector(policies, registry), registry);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(meta));
        FileMetadataRepository metadata = new FileMetadataRepository(jdbc);
        files = new FileIngestionService(policies, writer, metadata, tx, new EncryptionNotReady(), Clock.systemUTC());
        MeterReadingRepository repository = new MeterReadingRepository(jdbc, new DataSourceTransactionManager(meta));
        readings = new MeterReadingIngestionService(repository, metadata, writer, tx, Clock.systemUTC());
    }

    @AfterEach
    void cleanUp() {
        var user = new MapSqlParameterSource("user", USER);
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT storage_type, storage_key FROM dbo.files WHERE created_by = :user", user)) {
            StorageAdapter adapter = "S3".equals(row.get("storage_type")) ? s3 : db;
            adapter.delete((String) row.get("storage_key"));
        }
        for (String meter : meters) {
            jdbc.update("DELETE FROM dbo.meter_readings WHERE meter_id = :m", Map.of("m", meter));
        }
        jdbc.update("DELETE o FROM dbo.outage_records o JOIN dbo.files f ON f.id = o.file_id"
                + " WHERE f.created_by = :user", user);
        jdbc.update("DELETE FROM dbo.files WHERE created_by = :user", user);
    }

    @Test
    void outageOf5MbIsStoredInS3WithIdenticalHash() throws IOException {
        Path file = randomFile(5 * 1024 * 1024);

        FileRecord stored = files.upload(outage(file));

        assertThat(stored.storage()).isEqualTo(StorageType.S3);
        try (InputStream back = s3.get(stored.storageKey(), ByteRange.full())) {
            assertThat(hash(back)).isEqualTo(stored.sha256()).isEqualTo(hash(Files.newInputStream(file)));
        }
        Map<String, Object> row = jdbc.queryForMap("SELECT f.status, f.sha256, f.size_bytes, o.zone, o.station_id"
                + " FROM dbo.files f JOIN dbo.outage_records o ON o.file_id = f.id"
                + " WHERE f.id = CAST(:id AS UNIQUEIDENTIFIER)", Map.of("id", stored.id().toString()));
        assertThat(row).containsEntry("status", "CONFIRMED").containsEntry("size_bytes", Files.size(file))
                .containsEntry("zone", "Dobrogea").containsEntry("station_id", "ST-IT-1");
        assertThat((byte[]) row.get("sha256")).isEqualTo(stored.sha256());
    }

    @Test
    void sameBatchSent1000TimesInParallelGivesOneFile() throws Exception {
        String meter = newMeter();
        MeterReadingBatch batch = batch(meter, 96);
        byte[] body = ("{\"meterId\":\"" + meter + "\"}").getBytes(StandardCharsets.UTF_8);
        Map<Boolean, AtomicInteger> outcomes = new ConcurrentHashMap<>(Map.of(true, new AtomicInteger(),
                false, new AtomicInteger()));
        AtomicInteger inProgress = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<Future<UUID>> results = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                results.add(pool.submit(() -> {
                    while (true) {
                        try {
                            MeterReadingIngestionService.Result r = readings.ingest(body, batch, USER);
                            outcomes.get(r.duplicate()).incrementAndGet();
                            return r.fileId();
                        } catch (ApiException e) {
                            // Clientul retrimite cu aceeași cheie, ca simulatorul (503 BATCH_IN_PROGRESS).
                            assertThat(e.code()).isEqualTo("BATCH_IN_PROGRESS");
                            inProgress.incrementAndGet();
                            Thread.sleep(20);
                        }
                    }
                }));
            }
            UUID first = results.getFirst().get();
            for (Future<UUID> result : results) {
                assertThat(result.get()).isEqualTo(first);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes.get(false)).as("201").hasValue(1);
        assertThat(outcomes.get(true)).as("200").hasValue(999);
        assertThat(count("SELECT COUNT(*) FROM dbo.files WHERE created_by = :user AND category = N'METER_READING'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM dbo.meter_readings WHERE meter_id = :m AND file_id IS NOT NULL",
                meter)).isEqualTo(96);
        assertThat(count("SELECT COUNT(*) FROM dbo.meter_readings WHERE meter_id = :m", meter)).isEqualTo(96);
    }

    @Test
    void primaryDownUsesFallbackAndBothDownLeaveNoOrphans() throws IOException {
        s3Switch.up = false;
        FileRecord outage = files.upload(outage(randomFile(64 * 1024)));
        assertThat(outage.storage()).isEqualTo(StorageType.DB);
        assertThat(db.exists(outage.storageKey())).isTrue();

        dbSwitch.up = false;
        long before = count("SELECT COUNT(*) FROM dbo.files WHERE created_by = :user");
        assertThatThrownBy(() -> files.upload(outage(randomFile(1024))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
        String meter = newMeter();
        assertThatThrownBy(() -> readings.ingest(new byte[] {'{', '}'}, batch(meter, 4), USER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("STORAGE_UNAVAILABLE"));

        assertThat(count("SELECT COUNT(*) FROM dbo.files WHERE created_by = :user")).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM dbo.meter_readings WHERE meter_id = :m", meter)).isZero();
    }

    private FileIngestionService.Upload outage(Path file) throws IOException {
        return new FileIngestionService.Upload(Category.OUTAGE_RECORD, "avarie-it.bin", "application/octet-stream",
                Files.size(file), () -> Files.newInputStream(file),
                new FileRecord.OutageInfo("Dobrogea", "ST-IT-1", Instant.parse("2026-11-14T09:32:00Z")), USER);
    }

    private String newMeter() {
        String meter = "IT-C7-" + UUID.randomUUID();
        meters.add(meter);
        return meter;
    }

    private static MeterReadingBatch batch(String meter, int n) {
        Instant start = Instant.parse("2026-11-14T00:00:00Z");
        List<MeterReadingBatch.Reading> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new MeterReadingBatch.Reading(start.plusSeconds(900L * i), new BigDecimal("0.4" + i)));
        }
        return new MeterReadingBatch(meter, "Banat", list);
    }

    private Path randomFile(int size) throws IOException {
        Path file = Files.createTempFile(temp, "c7", ".bin");
        byte[] block = new byte[64 * 1024];
        Random random = new Random(size);
        try (OutputStream out = Files.newOutputStream(file)) {
            for (int written = 0; written < size; written += block.length) {
                random.nextBytes(block);
                out.write(block, 0, Math.min(block.length, size - written));
            }
        }
        return file;
    }

    private static byte[] hash(InputStream in) throws IOException {
        try (InputStream stream = Sha256.digesting(in)) {
            stream.transferTo(OutputStream.nullOutputStream());
            return ((java.security.DigestInputStream) stream).getMessageDigest().digest();
        }
    }

    private static long count(String sql, String... meter) {
        var p = new MapSqlParameterSource("user", USER).addValue("m", meter.length == 0 ? "" : meter[0]);
        return jdbc.queryForObject(sql, p, Long.class);
    }

    private static HikariDataSource pool(String url, String user, String password) {
        HikariDataSource pool = DataSourceBuilder.create().type(HikariDataSource.class).url(url).username(user)
                .password(password).build();
        pool.setMaximumPoolSize(20);
        pool.setConnectionTimeout(3000);
        pool.setInitializationFailTimeout(-1);
        return pool;
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

    /** Un storage real care poate fi „oprit”: health down și orice operație → indisponibil. */
    private static final class Switchable implements StorageAdapter {
        private final StorageAdapter target;
        volatile boolean up = true;

        Switchable(StorageAdapter target) {
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

        private StorageAdapter check() {
            if (!up) {
                throw new StorageUnavailableException(type(), "oprit în test");
            }
            return target;
        }
    }
}
