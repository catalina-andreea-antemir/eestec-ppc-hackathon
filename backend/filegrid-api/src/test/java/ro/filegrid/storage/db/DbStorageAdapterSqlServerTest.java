package ro.filegrid.storage.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.io.ClassPathResource;

import com.zaxxer.hikari.HikariDataSource;

import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.ChunkReceipt;
import ro.filegrid.storage.ChunkedUpload;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageObjectNotFoundException;
import ro.filegrid.storage.StorageUnavailableException;

/** From the Maven module: ./mvnw -Dfilegrid.sql.integration=true -Dtest=DbStorageAdapterSqlServerTest test */
@EnabledIfSystemProperty(named = "filegrid.sql.integration", matches = "true")
class DbStorageAdapterSqlServerTest {

    private static final String USER = "filegrid_storage_app";
    private static final String DB = "FileGridStorage";
    private static final int ROW_SIZE = 4 * 1024 * 1024;
    private static final int CHUNK_SIZE = 5 * 1024 * 1024;
    private static final String JDBC_URL = "jdbc:sqlserver://127.0.0.1:1433;databaseName=" + DB
            + ";encrypt=true;trustServerCertificate=true";

    private static HikariDataSource pool;
    private final Set<String> testKeys = new HashSet<>();
    private final Set<String> testVersions = new HashSet<>();
    private DbStorageAdapter adapter;
    private Connection observer;
    private String key;

    @BeforeAll
    static void connect() throws IOException, SQLException {
        pool = DataSourceBuilder.create().type(HikariDataSource.class)
                .url(JDBC_URL).username(USER).password(password()).build();
        var properties = new YamlPropertySourceLoader().load("config", new ClassPathResource("application.yml"));
        new Binder(ConfigurationPropertySources.from(properties)).bind("filegrid.storage-db.hikari",
                Bindable.ofInstance(pool));
        // Use the production C9 pool limit, including for concurrent puts.
        pool.setMaximumPoolSize(30);
        pool.setMinimumIdle(1);
        try (Connection probe = pool.getConnection(); Statement statement = probe.createStatement();
                ResultSet identity = statement.executeQuery("SELECT DB_NAME(), ORIGINAL_LOGIN(), USER_NAME()")) {
            assertThat(identity.next()).isTrue();
            assertThat(identity.getString(1)).isEqualTo(DB);
            assertThat(identity.getString(2)).isEqualTo(USER);
            assertThat(identity.getString(3)).isEqualTo(USER);
        }
    }

    @AfterAll
    static void disconnect() {
        if (pool != null) {
            pool.close();
        }
    }

    @BeforeEach
    void createTestKey() throws SQLException {
        adapter = new DbStorageAdapter(pool);
        observer = pool.getConnection();
        String generated = "o3-a-it-" + StorageKeys.newKey();
        assertThat(versions(generated)).isEmpty();
        key = generated;
        testKeys.add(key);
    }

    @AfterEach
    void cleanTestRows() throws SQLException {
        if (observer == null) {
            return;
        }
        // Plain JDBC on purpose, so a bug in the adapter cannot disable the cleanup.
        try (Connection cleanup = observer) {
            if (key == null) {
                return;
            }
            cleanup.setAutoCommit(false);
            try {
                for (String ownedKey : testKeys) {
                    versions(ownedKey).stream().map(Version::id).forEach(testVersions::add);
                }
                try (PreparedStatement chunks = prepare("DELETE FROM dbo.blob_chunks WHERE storage_key = ?")) {
                    for (String version : testVersions) {
                        chunks.setNString(1, version);
                        chunks.executeUpdate();
                    }
                }
                try (PreparedStatement metadata = prepare("DELETE FROM dbo.blob_versions WHERE logical_key = ?")) {
                    for (String ownedKey : testKeys) {
                        metadata.setNString(1, ownedKey);
                        metadata.executeUpdate();
                    }
                }
                cleanup.commit();
            } catch (SQLException | RuntimeException | Error e) {
                try {
                    cleanup.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        }
        try (Connection verification = pool.getConnection()) {
            observer = verification;
            for (String ownedKey : testKeys) {
                assertThat(versions(ownedKey)).isEmpty();
            }
            for (String version : testVersions) {
                assertThat(chunkCount(version)).isZero();
            }
        }
    }

    @Test
    void fiftyConcurrentDistinctKeyPutsPublishExactBytesWithoutRetries() throws Exception {
        List<String> keys = new ArrayList<>();
        for (int n = 0; n < 50; n++) {
            String owned = "o3-concurrent-" + StorageKeys.newKey();
            assertThat(versions(owned)).isEmpty();
            testKeys.add(owned); // Register before submitting, so failures are also cleaned up.
            keys.add(owned);
        }
        // Warm connections separately: measure SQL locking, not simultaneous TLS handshakes.
        List<Connection> warm = new ArrayList<>();
        try {
            for (int n = 0; n < 29; n++) {
                warm.add(pool.getConnection());
            }
        } finally {
            for (Connection connection : warm) {
                connection.close();
            }
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> writes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(50)) {
            for (int n = 0; n < keys.size(); n++) {
                final int seed = n;
                String owned = keys.get(n);
                writes.add(workers.submit(() -> {
                    start.await();
                    byte[] expected = new byte[64 * 1024 + seed];
                    new Random(seed).nextBytes(expected);
                    // No reset/reopen/retry support: bytes must be consumed once only.
                    InputStream source = new InputStream() {
                        private int position;
                        @Override
                        public int read() {
                            return position == expected.length ? -1 : expected[position++] & 0xff;
                        }
                    };
                    assertThat(adapter.put(owned, source, expected.length, null)).isEqualTo(expected.length);
                    assertThat(adapter.exists(owned)).isTrue();
                    try (InputStream stored = adapter.get(owned, ByteRange.full())) {
                        byte[] actual = stored.readAllBytes();
                        assertThat(actual).isEqualTo(expected);
                        assertThat(MessageDigest.getInstance("SHA-256").digest(actual))
                                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(expected));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> write : writes) {
                try {
                    write.get(45, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    failures.add(failure);
                }
            }
        }
        if (!failures.isEmpty()) {
            AssertionError failure = new AssertionError(failures.size() + " of 50 concurrent puts failed");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
        for (String owned : keys) {
            assertThat(versions(owned)).singleElement().satisfies(version -> {
                assertThat(version.published()).isTrue();
            });
        }
        // @AfterEach independently deletes exact owned keys/versions and verifies no rows remain.
    }

    @Test
    void streamsExactLengthAcrossSqlRowsAndDeletesIdempotently() throws Exception {
        assertThat(adapter.exists(key)).isFalse();
        assertThatThrownBy(() -> adapter.get(key, ByteRange.full()))
                .isInstanceOf(StorageObjectNotFoundException.class);
        byte[] bytes = new byte[ROW_SIZE + 8];
        new Random(1).nextBytes(bytes);
        ByteArrayInputStream source = new ByteArrayInputStream(bytes);

        assertThat(adapter.put(key, source, bytes.length - 1L, null)).isEqualTo(bytes.length - 1L);
        Version committed = publishedVersion();
        assertThat(source.available()).isOne();
        assertThat(adapter.exists(key)).isTrue();
        assertThat(chunkCount(committed.id())).isEqualTo(2);
        assertThat(read(ByteRange.full())).isEqualTo(Arrays.copyOf(bytes, bytes.length - 1));
        assertThat(read(ByteRange.of(ROW_SIZE - 3L, 9)))
                .isEqualTo(Arrays.copyOfRange(bytes, ROW_SIZE - 3, ROW_SIZE + 6));
        assertThat(read(ByteRange.of(ROW_SIZE + 5L, Long.MAX_VALUE)))
                .isEqualTo(Arrays.copyOfRange(bytes, ROW_SIZE + 5, bytes.length - 1));
        assertThat(read(ByteRange.from(bytes.length - 1L))).isEmpty();

        assertThat(adapter.delete(key)).isTrue();
        assertThat(adapter.delete(key)).isFalse();
        assertThat(adapter.exists(key)).isFalse();
        assertThat(versions(key)).isEmpty();
        assertThat(chunkCount(committed.id())).isZero();
    }

    @Test
    void replacementCommitsOnePublishedVersionAndRemovesOldRows() throws Exception {
        put(new byte[]{1, 2, 3});
        Version first = publishedVersion();

        put(new byte[]{4, 5});
        Version second = publishedVersion();

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.size()).isEqualTo(2);
        assertThat(read(ByteRange.full())).containsExactly(4, 5);
        assertThat(chunkCount(first.id())).isZero();
        assertThat(canAcquireExclusiveLock()).isTrue();
    }

    @Test
    void prematureEofRollsBackInsertedRowsAndPreservesExistingObject() throws Exception {
        put(new byte[]{7, 8, 9});
        Version original = publishedVersion();
        InputStream truncated = new ByteArrayInputStream(new byte[ROW_SIZE + 1]) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                if (available() == 1) {
                    // The first INSERT has finished. Read only the unconfirmed metadata of our own key.
                    try {
                        versions(key, " WITH (READUNCOMMITTED)").stream().map(Version::id)
                                .forEach(testVersions::add);
                    } catch (SQLException e) {
                        throw new AssertionError("Nu pot urmări versiunea de test înainte de rollback", e);
                    }
                }
                return super.read(bytes, offset, length);
            }
        };

        try (truncated) {
            assertThatThrownBy(() -> adapter.put(key, truncated, ROW_SIZE + 2L, null))
                    .isExactlyInstanceOf(StorageException.class).hasMessageContaining("sursa");
        }

        assertThat(testVersions).hasSize(2);
        assertThat(publishedVersion()).isEqualTo(original);
        for (String version : testVersions) {
            if (!version.equals(original.id())) {
                assertThat(chunkCount(version)).isZero();
            }
        }
        assertThat(read(ByteRange.full())).containsExactly(7, 8, 9);
        assertThat(chunkCount(original.id())).isOne();
        assertThat(canAcquireExclusiveLock()).isTrue();
        put(new byte[]{10});
        assertThat(read(ByteRange.full())).containsExactly(10);
    }

    @Test
    void transactionLockIsHeldDuringWritesAndReadsAndReleasedAfterCommitOrClose() throws Exception {
        int[] checkedDuringWrite = {0};
        try (InputStream source = new ByteArrayInputStream(new byte[]{3, 2, 1}) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                try {
                    assertThat(canAcquireExclusiveLock()).isFalse();
                    checkedDuringWrite[0]++;
                } catch (SQLException e) {
                    throw new AssertionError("Verificarea lockului în timpul scrierii a eșuat", e);
                }
                return super.read(bytes, offset, length);
            }
        }) {
            assertThat(adapter.put(key, source, 3, null)).isEqualTo(3);
        }
        assertThat(checkedDuringWrite[0]).isPositive();
        Version committed = publishedVersion();
        assertThat(canAcquireExclusiveLock()).isTrue();

        try (InputStream stream = adapter.get(key, ByteRange.full())) {
            assertThat(stream.read()).isEqualTo(3);
            assertThat(canAcquireExclusiveLock()).isFalse();
            assertThatThrownBy(() -> adapter.delete(key)).isInstanceOf(StorageUnavailableException.class);
            assertThat(stream.read()).isEqualTo(2);
        }

        assertThat(canAcquireExclusiveLock()).isTrue();
        assertThat(publishedVersion()).isEqualTo(committed);
        try (Connection reused = pool.getConnection(); Statement statement = reused.createStatement();
                ResultSet transaction = statement.executeQuery("SELECT @@TRANCOUNT")) {
            assertThat(transaction.next()).isTrue();
            assertThat(transaction.getInt(1)).isZero();
        }
    }

    @Test
    void applicationAccountHasOnlyTheRequiredStoragePrivileges() throws SQLException {
        assertThat(adapter.health().up()).isTrue();
        try (Statement statement = observer.createStatement(); ResultSet permissions = statement.executeQuery("""
                SELECT HAS_PERMS_BY_NAME('dbo.blob_versions', 'OBJECT', 'SELECT'),
                    HAS_PERMS_BY_NAME('dbo.blob_versions', 'OBJECT', 'INSERT'),
                    HAS_PERMS_BY_NAME('dbo.blob_versions', 'OBJECT', 'DELETE'),
                    HAS_PERMS_BY_NAME('dbo.blob_versions', 'OBJECT', 'UPDATE', 'published', 'COLUMN'),
                    HAS_PERMS_BY_NAME('dbo.blob_versions', 'OBJECT', 'UPDATE', 'size_bytes', 'COLUMN'),
                    HAS_PERMS_BY_NAME('dbo.blob_versions', 'OBJECT', 'UPDATE', 'retired_at', 'COLUMN'),
                    HAS_PERMS_BY_NAME('dbo.blob_versions', 'OBJECT', 'UPDATE', 'upload_chunk_size', 'COLUMN'),
                    HAS_PERMS_BY_NAME('dbo.blob_chunks', 'OBJECT', 'SELECT'),
                    HAS_PERMS_BY_NAME('dbo.blob_chunks', 'OBJECT', 'INSERT'),
                    HAS_PERMS_BY_NAME('dbo.blob_chunks', 'OBJECT', 'DELETE'),
                    HAS_PERMS_BY_NAME('dbo.blob_chunks', 'OBJECT', 'UPDATE'),
                    HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'CREATE TABLE')
                """)) {
            assertThat(permissions.next()).isTrue();
            List<Integer> granted = new ArrayList<>();
            for (int column = 1; column <= 12; column++) {
                granted.add(permissions.getObject(column, Integer.class));
            }
            assertThat(granted).containsExactly(1, 1, 1, 1, 0, 1, 0, 1, 1, 1, 0, 0);
        }
    }

    @Test
    void resumesMultipartAcrossInstancesAndAtomicallyReplacesARetransmittedPart() throws Exception {
        ChunkedUpload upload = open(CHUNK_SIZE + 3L);
        byte[] first = new byte[CHUNK_SIZE + 1];
        first[ROW_SIZE - 1] = 5;
        first[ROW_SIZE] = 6;
        first[CHUNK_SIZE - 1] = 7;
        ByteArrayInputStream source = new ByteArrayInputStream(first);
        DbStorageAdapter secondInstance = new DbStorageAdapter(pool);

        ChunkReceipt one = secondInstance.append(upload, 1, source, CHUNK_SIZE);
        assertThat(source.available()).isOne();
        append(upload, 2, new byte[]{1, 2, 3});
        ChunkReceipt two = append(upload, 2, new byte[]{8, 9, 10});
        assertThat(adapter.exists(key)).isFalse();
        assertThatThrownBy(() -> adapter.get(key, ByteRange.full())).isInstanceOf(StorageObjectNotFoundException.class);

        assertThat(secondInstance.finish(upload, List.of(one, two))).isEqualTo(CHUNK_SIZE + 3L);
        assertThat(adapter.finish(upload, List.of(one, two))).isEqualTo(CHUNK_SIZE + 3L);
        assertThat(publishedVersion().id()).isEqualTo("~" + upload.uploadRef());
        assertThat(read(ByteRange.of(ROW_SIZE - 1L, 2))).containsExactly(5, 6);
        assertThat(read(ByteRange.from(CHUNK_SIZE - 1L))).containsExactly(7, 8, 9, 10);
        assertThatThrownBy(() -> append(upload, 2, new byte[]{1, 1, 1})).isInstanceOf(StorageException.class);
        assertThat(read(ByteRange.from(CHUNK_SIZE))).containsExactly(8, 9, 10);
    }

    @Test
    void prematureEofDuringRetransmissionRestoresPreviousPart() throws Exception {
        ChunkedUpload upload = open(CHUNK_SIZE);
        byte[] first = new byte[CHUNK_SIZE];
        first[0] = 11;
        first[CHUNK_SIZE - 1] = 12;
        ChunkReceipt receipt = append(upload, 1, first);

        assertThatThrownBy(() -> adapter.append(upload, 1, new ByteArrayInputStream(new byte[ROW_SIZE + 1]),
                CHUNK_SIZE)).isExactlyInstanceOf(StorageException.class).hasMessageContaining("sursa");

        assertThat(adapter.finish(upload, List.of(receipt))).isEqualTo(CHUNK_SIZE);
        assertThat(read(ByteRange.full())).isEqualTo(first);
    }

    @Test
    void incompleteFinishPreservesPublishedObjectUntilMissingPartIsStored() throws Exception {
        put(new byte[]{14});
        Version original = publishedVersion();
        ChunkedUpload upload = open(CHUNK_SIZE + 2L);
        ChunkReceipt one = append(upload, 1, new byte[CHUNK_SIZE]);
        ChunkReceipt missing = new ChunkReceipt(2, 2, null);

        assertThatThrownBy(() -> adapter.finish(upload, List.of(one))).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> adapter.finish(upload, List.of(one, missing))).isInstanceOf(StorageException.class);
        assertThat(adapter.exists(key)).isTrue();
        assertThat(read(ByteRange.full())).containsExactly(14);
        assertThat(chunkCount(original.id())).isOne();

        ChunkReceipt two = append(upload, 2, new byte[]{15, 16});
        assertThat(adapter.finish(upload, List.of(one, two))).isEqualTo(CHUNK_SIZE + 2L);
        assertThat(chunkCount(original.id())).isZero();
        assertThat(read(ByteRange.from(CHUNK_SIZE))).containsExactly(15, 16);
    }

    @Test
    void finishRejectsOverlappingOrIncompletePhysicalRows() throws Exception {
        ChunkedUpload upload = open(4);
        ChunkReceipt receipt = append(upload, 1, new byte[]{1, 2, 3, 4});
        String version = "~" + upload.uploadRef();
        insertTestRow(version, 1, 2);
        assertThatThrownBy(() -> adapter.finish(upload, List.of(receipt))).isInstanceOf(StorageException.class);

        try (PreparedStatement delete = prepare("DELETE FROM dbo.blob_chunks WHERE storage_key = ?")) {
            delete.setNString(1, version);
            assertThat(delete.executeUpdate()).isEqualTo(2);
        }
        insertTestRow(version, 0, 3);
        assertThatThrownBy(() -> adapter.finish(upload, List.of(receipt))).isInstanceOf(StorageException.class);
        assertThat(adapter.exists(key)).isFalse();

        append(upload, 1, new byte[]{1, 2, 3, 4});
        assertThat(adapter.finish(upload, List.of(receipt))).isEqualTo(4);
    }

    @Test
    void abortBeforeFinishPreservesOlderPublishedObjectAndOtherSession() throws Exception {
        put(new byte[]{17});
        ChunkedUpload abandoned = open(1);
        ChunkedUpload remaining = open(1);
        append(abandoned, 1, new byte[]{18});
        ChunkReceipt receipt = append(remaining, 1, new byte[]{19});

        adapter.abort(abandoned);
        adapter.abort(abandoned);
        assertThat(chunkCount("~" + abandoned.uploadRef())).isZero();
        assertThat(read(ByteRange.full())).containsExactly(17);
        assertThat(adapter.finish(remaining, List.of(receipt))).isOne();
        assertThat(read(ByteRange.full())).containsExactly(19);

        adapter.abort(remaining);
        adapter.abort(remaining);
        assertThat(adapter.exists(key)).isFalse();
        assertThat(chunkCount("~" + remaining.uploadRef())).isZero();
    }

    @Test
    void staleAbortAndFinishCannotChangeNewerPublishedObject() throws Exception {
        ChunkedUpload first = open(1);
        ChunkedUpload second = open(1);
        List<ChunkReceipt> firstReceipts = List.of(append(first, 1, new byte[]{20}));
        List<ChunkReceipt> secondReceipts = List.of(append(second, 1, new byte[]{21}));
        adapter.finish(first, firstReceipts);
        assertThat(read(ByteRange.full())).containsExactly(20);
        adapter.finish(second, secondReceipts);

        assertThat(chunkCount("~" + first.uploadRef())).isZero();
        assertThat(adapter.finish(first, firstReceipts)).isOne();
        adapter.abort(first);
        assertThat(read(ByteRange.full())).containsExactly(21);

        put(new byte[]{22});
        adapter.abort(second);
        assertThat(adapter.finish(second, secondReceipts)).isOne();
        assertThat(read(ByteRange.full())).containsExactly(22);
    }

    @Test
    void deleteInvalidatesPendingSessionsAndRemovesTheirChunks() throws Exception {
        ChunkedUpload replaced = open(1);
        adapter.finish(replaced, List.of(append(replaced, 1, new byte[]{22})));
        put(new byte[]{23});
        ChunkedUpload upload = open(1);
        ChunkReceipt receipt = append(upload, 1, new byte[]{24});

        assertThat(adapter.delete(key)).isTrue();
        assertThat(adapter.delete(key)).isFalse();
        assertThat(versions(key)).isEmpty();
        assertThat(chunkCount("~" + upload.uploadRef())).isZero();
        assertThatThrownBy(() -> adapter.finish(upload, List.of(receipt))).isInstanceOf(StorageException.class);
        adapter.abort(upload);
    }

    @Test
    void uploadReferenceCannotModifyDataThroughADifferentKeyOrDimensions() throws Exception {
        ChunkedUpload upload = open(2);
        ChunkReceipt receipt = append(upload, 1, new byte[]{24, 25});
        ChunkedUpload foreign = new ChunkedUpload(StorageKeys.newKey(), upload.uploadRef(), 2, CHUNK_SIZE);
        ChunkedUpload changedSize = new ChunkedUpload(key, upload.uploadRef(), 2, CHUNK_SIZE + 1);

        assertThatThrownBy(() -> append(foreign, 1, new byte[]{26, 27})).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> adapter.finish(foreign, List.of(receipt))).isInstanceOf(StorageException.class);
        adapter.abort(foreign);
        assertThatThrownBy(() -> append(changedSize, 1, new byte[]{26, 27})).isInstanceOf(StorageException.class);

        assertThat(adapter.finish(upload, List.of(receipt))).isEqualTo(2);
        assertThat(read(ByteRange.full())).containsExactly(24, 25);
    }

    private ChunkedUpload open(long size) {
        String ref = adapter.open(key, size, CHUNK_SIZE);
        testVersions.add("~" + ref);
        return new ChunkedUpload(key, ref, size, CHUNK_SIZE);
    }

    private ChunkReceipt append(ChunkedUpload upload, int n, byte[] bytes) {
        return adapter.append(upload, n, new ByteArrayInputStream(bytes), bytes.length);
    }

    private void insertTestRow(String version, long offset, int length) throws SQLException {
        assertThat(testVersions).contains(version);
        try (PreparedStatement insert = prepare("""
                INSERT INTO dbo.blob_chunks (storage_key, chunk_offset, chunk_data) VALUES (?, ?, ?)
                """)) {
            insert.setNString(1, version);
            insert.setLong(2, offset);
            insert.setBytes(3, new byte[length]);
            insert.executeUpdate();
        }
    }

    private void put(byte[] bytes) throws SQLException {
        assertThat(adapter.put(key, new ByteArrayInputStream(bytes), bytes.length, null)).isEqualTo(bytes.length);
        versions(key).stream().map(Version::id).forEach(testVersions::add);
    }

    private byte[] read(ByteRange range) throws IOException {
        try (InputStream stream = adapter.get(key, range)) {
            return stream.readAllBytes();
        }
    }

    private Version publishedVersion() throws SQLException {
        List<Version> found = versions(key);
        found.stream().map(Version::id).forEach(testVersions::add);
        assertThat(found).singleElement().satisfies(version -> assertThat(version.published()).isTrue());
        return found.getFirst();
    }

    private List<Version> versions(String logicalKey) throws SQLException {
        return versions(logicalKey, "");
    }

    private List<Version> versions(String logicalKey, String tableHint) throws SQLException {
        try (PreparedStatement statement = prepare("SELECT version_key, size_bytes, published FROM dbo.blob_versions"
                + tableHint + " WHERE logical_key = ?")) {
            statement.setNString(1, logicalKey);
            try (ResultSet result = statement.executeQuery()) {
                List<Version> found = new ArrayList<>();
                while (result.next()) {
                    found.add(new Version(result.getString(1), result.getLong(2), result.getBoolean(3)));
                }
                return found;
            }
        }
    }

    private int chunkCount(String version) throws SQLException {
        try (PreparedStatement statement = prepare("SELECT COUNT(*) FROM dbo.blob_chunks WHERE storage_key = ?")) {
            statement.setNString(1, version);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }
    }

    private boolean canAcquireExclusiveLock() throws SQLException {
        try (PreparedStatement statement = prepare("SELECT APPLOCK_TEST(N'public', ?, N'Exclusive', N'Session')")) {
            statement.setNString(1, "FileGridStorage:" + key);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject(1, Integer.class)).isIn(0, 1);
                return result.getInt(1) == 1;
            }
        }
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        PreparedStatement statement = observer.prepareStatement(sql);
        statement.setQueryTimeout(4);
        return statement;
    }

    private static String password() throws IOException {
        String password = System.getenv("STORAGE_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            Path env = Path.of("..", "..", ".env");
            String prefix = "STORAGE_DB_PASSWORD=";
            if (Files.isRegularFile(env)) {
                // Same KEY=value format used by scripts/start.*. The file is never executed.
                try (var lines = Files.lines(env)) {
                    password = lines.filter(line -> line.startsWith(prefix)).map(line -> line.substring(prefix.length()))
                            .reduce((first, last) -> last).orElse(null);
                }
            }
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("STORAGE_DB_PASSWORD lipsește din mediu și din .env");
        }
        return password;
    }

    private record Version(String id, long size, boolean published) {
    }
}
