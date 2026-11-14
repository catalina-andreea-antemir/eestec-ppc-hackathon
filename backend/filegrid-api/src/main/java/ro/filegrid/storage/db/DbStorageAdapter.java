package ro.filegrid.storage.db;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import ro.filegrid.config.DataSourceConfig;
import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageHealth;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageObjectNotFoundException;
import ro.filegrid.storage.StorageUnavailableException;

/** Storage de octeți în FileGridStorage, cu tranzacții locale pe conexiunea JDBC proprie. */
@Repository
@Profile("db")
public class DbStorageAdapter implements StorageAdapter {

    private static final StorageType TYPE = StorageType.DB;
    private static final int ROW_SIZE = 4 * 1024 * 1024;
    private static final int QUERY_TIMEOUT_SECONDS = 4;
    private static final int HEALTH_TIMEOUT_MILLIS = 2_000;

    private static final String FIND_PUBLISHED = """
            SELECT version_key, size_bytes FROM dbo.blob_versions WHERE logical_key = ? AND published = 1
            """;
    private static final String READ_ROW = """
            SELECT TOP (1) chunk_offset, chunk_size, SUBSTRING(chunk_data, ? - chunk_offset + 1, ?)
            FROM dbo.blob_chunks
            WHERE storage_key = ? AND chunk_offset <= ?
            ORDER BY chunk_offset DESC
            """;
    private static final String LOCK_KEY = """
            DECLARE @result int;
            EXEC @result = sys.sp_getapplock @Resource = ?, @LockMode = ?,
                @LockOwner = N'Transaction', @LockTimeout = 1000;
            SELECT @result;
            """;

    private final DataSource dataSource;

    public DbStorageAdapter(@Qualifier(DataSourceConfig.STORAGE_DATA_SOURCE) DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public StorageType type() {
        return TYPE;
    }

    @Override
    public long put(String key, InputStream data, long length, String contentType) {
        StorageKeys.requireValid(key);
        Objects.requireNonNull(data, "data");
        if (length <= 0) {
            throw new IllegalArgumentException("length trebuie să fie pozitiv");
        }
        // Prefixul rezervat separă cheile fizice de cheile logice valide din schema inițială.
        String version = "~" + UUID.randomUUID();
        return mutate(key, "put", connection -> {
            try (PreparedStatement insert = prepare(connection, """
                    INSERT INTO dbo.blob_versions (version_key, logical_key, size_bytes, published) VALUES (?, ?, ?, 0)
                    """)) {
                insert.setNString(1, version);
                insert.setNString(2, key);
                insert.setLong(3, length);
                insert.executeUpdate();
            }
            writeRows(connection, version, data, length);
            deleteVersions(connection, key, true);
            try (PreparedStatement publish = prepare(connection, """
                    UPDATE dbo.blob_versions SET published = 1 WHERE version_key = ?
                    """)) {
                publish.setNString(1, version);
                if (publish.executeUpdate() != 1) {
                    throw new StorageException(TYPE, "DB put: versiunea nu a fost publicată");
                }
            }
            return length;
        });
    }

    private static void writeRows(Connection connection, String version, InputStream data, long length)
            throws SQLException {
        try (PreparedStatement insert = prepare(connection, """
                INSERT INTO dbo.blob_chunks (storage_key, chunk_offset, chunk_data) VALUES (?, ?, ?)
                """)) {
            insert.setNString(1, version);
            long offset = 0;
            while (offset < length) {
                int size = (int) Math.min(ROW_SIZE, length - offset);
                RowSource row = new RowSource(data, size);
                insert.setLong(2, offset);
                try {
                    insert.setBinaryStream(3, row, (long) size);
                    insert.executeUpdate();
                } catch (SQLException e) {
                    if (row.failure != null) {
                        throw sourceError(row.failure);
                    }
                    throw e;
                }
                if (row.remaining != 0) {
                    throw sourceError(row.failure);
                }
                offset += size;
            }
        }
    }

    @Override
    public InputStream get(String key, ByteRange range) {
        StorageKeys.requireValid(key);
        Objects.requireNonNull(range, "range");
        try {
            Connection connection = dataSource.getConnection();
            long length;
            try {
                beginLocked(connection, key, "Shared");
                String version;
                try (PreparedStatement find = prepare(connection, FIND_PUBLISHED)) {
                    find.setNString(1, key);
                    try (ResultSet result = find.executeQuery()) {
                        if (!result.next()) {
                            throw new StorageObjectNotFoundException(TYPE, "solicitat");
                        }
                        version = result.getString(1);
                        length = range.lengthWithin(result.getLong(2));
                    }
                }
                if (length > 0) {
                    return new ObjectStream(connection, prepare(connection, READ_ROW), version, range.offset(), length);
                }
            } catch (SQLException | RuntimeException | Error e) {
                try {
                    closeRead(connection);
                } catch (SQLException closing) {
                    e.addSuppressed(closing);
                }
                throw e;
            }
            closeRead(connection);
            return InputStream.nullInputStream();
        } catch (SQLException e) {
            throw translate("get", e);
        }
    }

    @Override
    public boolean exists(String key) {
        StorageKeys.requireValid(key);
        return inTransaction(key, "Shared", "exists", connection -> {
            try (PreparedStatement find = prepare(connection, FIND_PUBLISHED)) {
                find.setNString(1, key);
                try (ResultSet result = find.executeQuery()) {
                    return result.next();
                }
            }
        });
    }

    @Override
    public boolean delete(String key) {
        StorageKeys.requireValid(key);
        return mutate(key, "delete", connection -> deleteVersions(connection, key, false) > 0);
    }

    private static int deleteVersions(Connection connection, String key, boolean publishedOnly) throws SQLException {
        String filter = publishedOnly ? " AND v.published = 1" : "";
        try (PreparedStatement chunks = prepare(connection, """
                DELETE c FROM dbo.blob_chunks c INNER JOIN dbo.blob_versions v ON c.storage_key = v.version_key
                WHERE v.logical_key = ?
                """ + filter);
                PreparedStatement versions = prepare(connection,
                        "DELETE v FROM dbo.blob_versions v WHERE v.logical_key = ?" + filter)) {
            chunks.setNString(1, key);
            chunks.executeUpdate();
            versions.setNString(1, key);
            return versions.executeUpdate();
        }
    }

    @Override
    public StorageHealth health() {
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            long remaining = HEALTH_TIMEOUT_MILLIS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (remaining > 0) {
                connection.setNetworkTimeout(Runnable::run, (int) Math.min(remaining, 1_000));
                // Driverul SQL Server implementează isValid prin SELECT 1.
                if (connection.isValid(1) && System.nanoTime() - start < TimeUnit.MILLISECONDS
                        .toNanos(HEALTH_TIMEOUT_MILLIS)) {
                    return StorageHealth.up(TYPE, Duration.ofNanos(System.nanoTime() - start));
                }
            }
        } catch (SQLException | RuntimeException e) {
            // Doar starea și durata ies din health, fără detalii ale conexiunii.
        }
        return StorageHealth.down(TYPE, Duration.ofNanos(System.nanoTime() - start), "FileGridStorage indisponibil");
    }

    private <T> T mutate(String key, String operation, SqlOperation<T> action) {
        return inTransaction(key, "Exclusive", operation, action);
    }

    private <T> T inTransaction(String key, String lockMode, String operation, SqlOperation<T> action) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                beginLocked(connection, key, lockMode);
                T result = action.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                try {
                    if (!connection.getAutoCommit()) {
                        connection.rollback();
                    }
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw translate(operation, e);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run(Connection connection) throws SQLException;
    }

    private static void beginLocked(Connection connection, String key, String mode) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement begin = connection.createStatement()) {
            begin.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            // Un batch direct pornește tranzacția înainte de sp_getapplock. Cu IMPLICIT_TRANSACTIONS ON,
            // BEGIN TRANSACTION ar crea două niveluri, iar Connection.commit() ar elibera doar unul.
            begin.execute("SET IMPLICIT_TRANSACTIONS OFF; BEGIN TRANSACTION; SET IMPLICIT_TRANSACTIONS ON;");
        }
        try (PreparedStatement lock = prepare(connection, LOCK_KEY)) {
            lock.setNString(1, "FileGridStorage:" + key);
            lock.setNString(2, mode);
            try (ResultSet result = lock.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Rezultatul sp_getapplock lipsește");
                }
                int code = result.getInt(1);
                if (code == -1 || code == -2 || code == -3) {
                    throw new SQLTimeoutException("Accesul concurent la obiect nu a putut fi obținut");
                }
                if (code < 0) {
                    throw new SQLException("sp_getapplock a eșuat", null, code);
                }
            }
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            return statement;
        } catch (SQLException | RuntimeException e) {
            try (statement) {
                throw e;
            }
        }
    }

    private static void closeRead(Connection connection) throws SQLException {
        try (connection) {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
        }
    }

    private static StorageException translate(String operation, SQLException error) {
        for (SQLException e = error; e != null; e = e.getNextException()) {
            String state = e.getSQLState();
            if (e instanceof SQLTimeoutException || e instanceof SQLTransientConnectionException
                    || e instanceof SQLNonTransientConnectionException || e instanceof SQLRecoverableException
                    || (state != null && (state.startsWith("08") || state.startsWith("HYT") || state.equals("S1T00")))
                    || e.getErrorCode() == 942 || e.getErrorCode() == 4060
                    || e.getErrorCode() == 1205 || e.getErrorCode() == 1222) {
                return new StorageUnavailableException(TYPE, "DB " + operation + ": FileGridStorage indisponibil", error);
            }
        }
        return new StorageException(TYPE, "DB " + operation + ": operația SQL a eșuat", error);
    }

    private static StorageException sourceError(IOException error) {
        return new StorageException(TYPE, "DB put: sursa s-a întrerupt înainte de lungimea declarată", error);
    }

    /** Vedere limitată la un rând SQL. Sursa aparține apelantului, inclusiv când driverul închide vederea. */
    private static final class RowSource extends InputStream {
        private final InputStream source;
        private int remaining;
        private IOException failure;

        private RowSource(InputStream source, int length) {
            this.source = source;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            try {
                int value = source.read();
                if (value < 0) {
                    throw new EOFException();
                }
                remaining--;
                return value;
            } catch (IOException e) {
                failure = e;
                throw e;
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            try {
                int count = source.read(bytes, offset, Math.min(length, remaining));
                if (count < 0) {
                    throw new EOFException();
                }
                remaining -= count;
                return count;
            } catch (IOException e) {
                failure = e;
                throw e;
            }
        }
    }

    /** Păstrează lockul partajat până la EOF/close. O singură bucată SQL poate fi în curs de citire. */
    private static final class ObjectStream extends InputStream {
        private final Connection connection;
        private final PreparedStatement statement;
        private final String version;
        private final byte[] singleByte = new byte[1];
        private long position;
        private long remaining;
        private ResultSet row;
        private InputStream data;
        private int rowRemaining;
        private boolean closed;

        private ObjectStream(Connection connection, PreparedStatement statement, String version, long offset, long length) {
            this.connection = connection;
            this.statement = statement;
            this.version = version;
            this.position = offset;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            return read(singleByte, 0, 1) == -1 ? -1 : singleByte[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            if (closed) {
                throw new IOException("DB get: stream închis");
            }
            try {
                if (row == null) {
                    openRow();
                }
                int count = data.read(bytes, offset, Math.min(length, rowRemaining));
                if (count < 0) {
                    throw new EOFException("DB get: rând binar incomplet");
                }
                position += count;
                remaining -= count;
                rowRemaining -= count;
                if (remaining == 0) {
                    close();
                } else if (rowRemaining == 0) {
                    closeRow();
                }
                return count;
            } catch (SQLException | IOException | RuntimeException e) {
                try (this) {
                    throw new IOException("DB get: citire întreruptă", e);
                }
            }
        }

        private void openRow() throws SQLException, IOException {
            statement.setLong(1, position);
            statement.setInt(2, (int) Math.min(remaining, ROW_SIZE));
            statement.setNString(3, version);
            statement.setLong(4, position);
            row = statement.executeQuery();
            if (!row.next()) {
                throw new EOFException("DB get: lipsește un rând binar");
            }
            long rowOffset = row.getLong(1);
            int rowSize = row.getInt(2);
            long available = rowSize - (position - rowOffset);
            if (available <= 0 || rowSize > ROW_SIZE) {
                throw new EOFException("DB get: offset sau mărime invalidă în blob_chunks");
            }
            rowRemaining = (int) Math.min(remaining, available);
            data = row.getBinaryStream(3);
        }

        private void closeRow() throws IOException, SQLException {
            try (ResultSet result = row; InputStream blob = data) {
                // Închide streamul înaintea rezultatului, inclusiv la ieșire anticipată.
            } finally {
                row = null;
                data = null;
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            // Resursele se închid în ordinea inversă, inclusiv dacă eliberarea uneia eșuează.
            try (AutoCloseable transaction = () -> closeRead(connection); statement) {
                closeRow();
            } catch (Exception e) {
                throw new IOException("DB get: închiderea streamului a eșuat", e);
            }
        }
    }
}
