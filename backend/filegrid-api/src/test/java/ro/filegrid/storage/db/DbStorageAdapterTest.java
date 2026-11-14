package ro.filegrid.storage.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.ChunkReceipt;
import ro.filegrid.storage.ChunkedUpload;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageObjectNotFoundException;
import ro.filegrid.storage.StorageUnavailableException;

class DbStorageAdapterTest {

    private record BinaryParameter(InputStream stream, long length) {
    }

    private static final String KEY = "test-logical-key";
    private static final String VERSION = "~8c4ab782-d915-4d1a-ba26-ffd4e4da1682";
    private static final int ROW_SIZE = 4 * 1024 * 1024;
    private static final int CHUNK_SIZE = 5 * 1024 * 1024;
    private static final String UPLOAD_REF = VERSION.substring(1);

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DbStorageAdapter adapter = new DbStorageAdapter(dataSource);

    @BeforeEach
    void connect() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        PreparedStatement lock = statement("sp_getapplock");
        ResultSet result = resultOf(lock);
        when(result.next()).thenReturn(true);
    }

    @Test
    void putConsumesExactlyLengthInBoundedRowsAndLeavesSourceOpen() throws Exception {
        PreparedStatement insert = preparePut();
        int length = ROW_SIZE + 3;
        var source = new ByteArrayInputStream(new byte[length + 1]) {
            @Override
            public void close() {
                throw new AssertionError("Sursa aparține apelantului");
            }
        };

        assertThat(adapter.put(KEY, source, length, null)).isEqualTo(length);
        assertThat(source.available()).isEqualTo(1);
        InOrder order = inOrder(insert);
        order.verify(insert).setLong(2, 0);
        order.verify(insert).setBinaryStream(eq(3), any(InputStream.class), eq((long) ROW_SIZE));
        order.verify(insert).setLong(2, ROW_SIZE);
        order.verify(insert).setBinaryStream(eq(3), any(InputStream.class), eq(3L));
        verify(connection).commit();
        verify(connection).close();
    }

    @Test
    void replacementPublishesOnlyAfterAllRowsHaveBeenWrittenAndOldVersionRemoved() throws Exception {
        PreparedStatement insert = preparePut();
        PreparedStatement oldRows = statement("DELETE c FROM");
        PreparedStatement oldVersion = statement("AND published = 1 AND upload_chunk_size IS NULL");
        PreparedStatement publish = statement("UPDATE dbo.blob_versions SET published = 1");
        when(publish.executeUpdate()).thenReturn(1);

        assertThat(adapter.put(KEY, new ByteArrayInputStream(new byte[]{1, 2}), 2, null)).isEqualTo(2);

        verify(connection).prepareStatement(matches("(?s).*WHERE v\\.logical_key = \\?\\s+AND v\\.published = 1.*"));
        InOrder order = inOrder(connection, insert, oldRows, oldVersion, publish);
        order.verify(connection).setAutoCommit(false);
        order.verify(insert).executeUpdate();
        order.verify(oldRows).executeUpdate();
        order.verify(oldVersion).executeUpdate();
        order.verify(publish).executeUpdate();
        order.verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void earlyEofRollsBackWithoutTouchingOldVersion() throws Exception {
        preparePut();

        assertThatThrownBy(() -> adapter.put(KEY, new ByteArrayInputStream(new byte[ROW_SIZE]), ROW_SIZE + 1L, null))
                .isExactlyInstanceOf(StorageException.class).hasMessageContaining("sursa");

        verify(connection, never()).prepareStatement(contains("DELETE"));
        verify(connection, never()).commit();
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    void publicationFailureRollsBackTheDeletionAndReportsSqlFailure() throws Exception {
        preparePut();
        PreparedStatement oldRows = statement("DELETE c FROM");
        PreparedStatement oldVersion = statement("AND published = 1 AND upload_chunk_size IS NULL");
        PreparedStatement publish = statement("UPDATE dbo.blob_versions SET published = 1");
        when(publish.executeUpdate()).thenThrow(new SQLException("sensitive SQL error", "23000", 2601));

        assertThatThrownBy(() -> adapter.put(KEY, new ByteArrayInputStream(new byte[]{1}), 1, null))
                .isExactlyInstanceOf(StorageException.class).hasMessageNotContaining("sensitive");

        InOrder order = inOrder(oldRows, oldVersion, publish, connection);
        order.verify(oldRows).executeUpdate();
        order.verify(oldVersion).executeUpdate();
        order.verify(publish).executeUpdate();
        order.verify(connection).rollback();
        order.verify(connection).close();
        verify(connection, never()).commit();
    }

    @Test
    void deleteReportsRemovedVersionOnlyOnceIncludingPendingVersions() throws Exception {
        PreparedStatement chunks = statement("DELETE c FROM");
        PreparedStatement versions = statement("AND retired_at IS NULL");
        statement("AND retired_at IS NOT NULL");
        when(versions.executeUpdate()).thenReturn(1, 0);

        assertThat(adapter.delete(KEY)).isTrue();
        assertThat(adapter.delete(KEY)).isFalse();

        verify(chunks, times(2)).executeUpdate();
        verify(connection, times(2)).prepareStatement(contains(
                "DELETE FROM dbo.blob_versions WHERE logical_key = ? AND retired_at IS NULL"));
        verify(connection, times(2)).commit();
    }

    @Test
    void openPersistsAUniqueReferenceAndDoesNotTouchPublishedData() throws Exception {
        PreparedStatement insert = statement("INSERT INTO dbo.blob_versions");

        String first = adapter.open(KEY, 7, CHUNK_SIZE);
        String second = adapter.open(KEY, 7, CHUNK_SIZE);

        assertThat(UUID.fromString(first).toString()).isEqualTo(first).isNotEqualTo(second);
        verify(insert).setNString(1, "~" + first);
        verify(insert).setNString(1, "~" + second);
        verify(insert, times(2)).setNString(2, KEY);
        verify(insert, times(2)).setLong(3, 7);
        verify(connection, times(2)).commit();
        verify(connection, never()).prepareStatement(contains("DELETE"));
    }

    @Test
    void multipartRejectsInvalidArgumentsBeforeOpeningAConnection() throws Exception {
        ChunkedUpload valid = new ChunkedUpload(KEY, UPLOAD_REF, 5, CHUNK_SIZE);
        ChunkedUpload badRef = new ChunkedUpload(KEY, "1-2-3-4-5", 5, CHUNK_SIZE);
        InputStream source = InputStream.nullInputStream();

        assertThatThrownBy(() -> adapter.open(KEY, Long.MAX_VALUE, CHUNK_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.open(KEY, 7, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.open(KEY, 0, CHUNK_SIZE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.append(badRef, 1, source, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.append(valid, 2, source, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.append(valid, 1, source, 4)).isInstanceOf(IllegalArgumentException.class);
        verify(dataSource, never()).getConnection();
    }

    @Test
    void appendReplacesOnlyThePartIntervalAndWritesAtAbsoluteOffsets() throws Exception {
        ChunkedUpload upload = new ChunkedUpload(KEY, UPLOAD_REF, 2L * CHUNK_SIZE, CHUNK_SIZE);
        uploadState(upload, false, false);
        PreparedStatement delete = statement("AND chunk_offset >= ? AND chunk_offset < ?");
        PreparedStatement insert = prepareChunkInsert();
        var source = new ByteArrayInputStream(new byte[CHUNK_SIZE + 1]) {
            @Override
            public void close() {
                throw new AssertionError("Sursa aparține apelantului");
            }
        };

        assertThat(adapter.append(upload, 2, source, CHUNK_SIZE)).isEqualTo(new ChunkReceipt(2, CHUNK_SIZE, null));
        assertThat(source.available()).isOne();

        verify(delete).setNString(1, VERSION);
        verify(delete).setLong(2, CHUNK_SIZE);
        verify(delete).setLong(3, 2L * CHUNK_SIZE);
        InOrder order = inOrder(delete, insert, connection);
        order.verify(delete).executeUpdate();
        order.verify(insert).setLong(2, CHUNK_SIZE);
        order.verify(insert).setBinaryStream(eq(3), any(InputStream.class), eq((long) ROW_SIZE));
        order.verify(insert).setLong(2, CHUNK_SIZE + ROW_SIZE);
        order.verify(insert).setBinaryStream(eq(3), any(InputStream.class), eq((long) CHUNK_SIZE - ROW_SIZE));
        order.verify(connection).commit();
    }

    @Test
    void failedAppendRollsBackThePartDeletion() throws Exception {
        ChunkedUpload upload = new ChunkedUpload(KEY, UPLOAD_REF, 8, CHUNK_SIZE);
        uploadState(upload, false, false);
        PreparedStatement delete = statement("AND chunk_offset >= ? AND chunk_offset < ?");
        PreparedStatement insert = prepareChunkInsert();

        assertThatThrownBy(() -> adapter.append(upload, 1, new ByteArrayInputStream(new byte[7]), 8))
                .isExactlyInstanceOf(StorageException.class).hasMessageContaining("sursa");

        InOrder order = inOrder(delete, insert, connection);
        order.verify(delete).executeUpdate();
        order.verify(insert).executeUpdate();
        order.verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void finishRejectsInvalidReceiptsBeforeAccessingSql() throws Exception {
        ChunkedUpload upload = new ChunkedUpload(KEY, UPLOAD_REF, CHUNK_SIZE + 1L, CHUNK_SIZE);
        ChunkReceipt first = new ChunkReceipt(1, CHUNK_SIZE, null);
        ChunkReceipt last = new ChunkReceipt(2, 1, null);

        assertThatThrownBy(() -> adapter.finish(upload, List.of(first))).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> adapter.finish(upload, List.of(last, first)))
                .isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> adapter.finish(upload, List.of(first, new ChunkReceipt(2, 2, null))))
                .isInstanceOf(StorageException.class);
        verify(dataSource, never()).getConnection();
    }

    @Test
    void finishDoesNotPublishEarlierCompletedUploadsAgain() throws Exception {
        ChunkedUpload upload = new ChunkedUpload(KEY, UPLOAD_REF, 5, CHUNK_SIZE);
        ResultSet state = uploadState(upload, true, false);
        when(state.getBoolean(3)).thenReturn(true, false);
        when(state.getBoolean(4)).thenReturn(true);
        var receipts = List.of(new ChunkReceipt(1, 5, null));

        assertThat(adapter.finish(upload, receipts)).isEqualTo(5);
        assertThat(adapter.finish(upload, receipts)).isEqualTo(5);

        verify(connection, never()).prepareStatement(contains("UPDATE dbo.blob_versions"));
        verify(connection, never()).prepareStatement(contains("ORDER BY chunk_offset"));
        verify(connection, times(2)).commit();
    }

    @Test
    void abortDeletesOnlyTheVersionOwnedByTheRequestedLogicalKey() throws Exception {
        ChunkedUpload upload = new ChunkedUpload(KEY, UPLOAD_REF, 5, CHUNK_SIZE);
        ResultSet state = uploadState(upload, true, false);
        when(state.next()).thenReturn(true, false);
        PreparedStatement chunks = statement("DELETE FROM dbo.blob_chunks WHERE storage_key = ?");
        PreparedStatement metadata = statement("DELETE FROM dbo.blob_versions WHERE version_key = ?");

        adapter.abort(upload);
        adapter.abort(upload);

        verify(chunks).setNString(1, VERSION);
        verify(metadata).setNString(1, VERSION);
        verify(chunks).executeUpdate();
        verify(metadata).executeUpdate();
        verify(connection, times(2)).commit();
    }

    @Test
    void missingObjectIsReportedAtOpenAndExistsReturnsFalse() throws Exception {
        ResultSet missing = resultOf(statement("AND published = 1"));

        assertThatThrownBy(() -> adapter.get(KEY, ByteRange.full()))
                .isInstanceOf(StorageObjectNotFoundException.class).hasMessageNotContaining(KEY);
        assertThat(adapter.exists(KEY)).isFalse();

        verify(missing, times(2)).close();
        verify(connection, times(2)).close();
        verify(connection, never()).prepareStatement(contains("SUBSTRING"));
    }

    @Test
    void rangeSeeksAcrossPhysicalRowsAndClampsWithoutOverflow() throws Exception {
        long firstOffset = 3_000_000_000L;
        long nextOffset = firstOffset + ROW_SIZE;
        published(nextOffset + 2);
        PreparedStatement read = statement("SUBSTRING");
        ResultSet first = row(firstOffset, ROW_SIZE, new ByteArrayInputStream(new byte[]{4}));
        ResultSet last = row(nextOffset, 2, new ByteArrayInputStream(new byte[]{5, 6}));
        when(read.executeQuery()).thenReturn(first, last);

        try (InputStream stream = adapter.get(KEY, ByteRange.of(nextOffset - 1, Long.MAX_VALUE))) {
            assertThat(stream.readAllBytes()).containsExactly(4, 5, 6);
            verify(connection).close();
        }

        InOrder order = inOrder(read, first, last);
        order.verify(read).setLong(1, nextOffset - 1);
        order.verify(read).setInt(2, 3);
        order.verify(read).setNString(3, VERSION);
        order.verify(read).setLong(4, nextOffset - 1);
        order.verify(first).close();
        order.verify(read).setLong(1, nextOffset);
        order.verify(read).setInt(2, 2);
        order.verify(last).close();
        verify(connection).rollback();
    }

    @Test
    void rangeAtOrBeyondEofReturnsEmptyWithoutOpeningBinaryRows() throws Exception {
        published(5);
        assertThat(adapter.get(KEY, ByteRange.from(5)).read()).isEqualTo(-1);
        assertThat(adapter.get(KEY, ByteRange.from(6)).read()).isEqualTo(-1);
        verify(connection, times(2)).rollback();
        verify(connection, times(2)).close();
        verify(connection, never()).prepareStatement(contains("SUBSTRING"));
    }

    @Test
    void earlyCloseReleasesEveryResourceEvenIfClosingTheBlobFails() throws Exception {
        published(5);
        PreparedStatement read = statement("SUBSTRING");
        InputStream blob = mock(InputStream.class);
        when(blob.read(any(byte[].class), eq(0), eq(1))).thenReturn(1);
        doThrow(new IOException("close failed")).when(blob).close();
        ResultSet row = row(0, 5, blob);
        when(read.executeQuery()).thenReturn(row);

        InputStream stream = adapter.get(KEY, ByteRange.full());
        stream.read();
        assertThatThrownBy(stream::close).isInstanceOf(IOException.class);
        stream.close();

        InOrder order = inOrder(blob, row, read, connection);
        order.verify(blob).close();
        order.verify(row).close();
        order.verify(read).close();
        order.verify(connection).rollback();
        order.verify(connection).close();
        verify(connection).close();
    }

    @Test
    void sqlFailureDuringStreamingBecomesIOExceptionAndClosesTheConnection() throws Exception {
        published(10);
        PreparedStatement read = statement("SUBSTRING");
        when(read.executeQuery()).thenThrow(new SQLException("connection lost", "08S01"));

        try (InputStream stream = adapter.get(KEY, ByteRange.full())) {
            assertThatThrownBy(stream::read).isInstanceOf(IOException.class).hasCauseInstanceOf(SQLException.class);
            verify(read).close();
            verify(connection).rollback();
            verify(connection).close();
        }
    }

    @Test
    void databaseOfflineAndPoolTimeoutMapToUnavailable() throws Exception {
        var offline = new SQLException("private server name", null, 942);
        when(dataSource.getConnection()).thenThrow(offline, new SQLTransientConnectionException("pool timeout"));

        assertThatThrownBy(() -> adapter.exists(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasMessageNotContaining("private server name");
        assertThatThrownBy(() -> adapter.get(KEY, ByteRange.full())).isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void applicationLockTimeoutRollsBackAndMapsToUnavailable() throws Exception {
        PreparedStatement lock = statement("sp_getapplock");
        ResultSet result = resultOf(lock);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(-1);

        assertThatThrownBy(() -> adapter.delete(KEY)).isInstanceOf(StorageUnavailableException.class);
        verify(connection).rollback();
        verify(connection).close();
        verify(connection, never()).prepareStatement(contains("DELETE"));
    }

    @Test
    void healthUsesABoundedProbeAndReportsUpOrDownWithoutLeakingDetails() throws Exception {
        when(connection.isValid(1)).thenReturn(true, false).thenThrow(new SQLException("private server name"));

        assertThat(adapter.type()).isEqualTo(StorageType.DB);
        assertThat(adapter.health().up()).isTrue();
        assertThat(adapter.health().up()).isFalse();
        var failure = adapter.health();
        assertThat(failure.up()).isFalse();
        assertThat(failure.detail()).doesNotContain("private server name");
        verify(connection, times(3)).setNetworkTimeout(any(Executor.class), eq(1_000));
        verify(connection, times(3)).close();
    }

    private PreparedStatement preparePut() throws Exception {
        statement("INSERT INTO dbo.blob_versions");
        statement("DELETE c FROM");
        statement("AND published = 1 AND upload_chunk_size IS NULL");
        statement("SET published = 0, retired_at");
        PreparedStatement publish = statement("UPDATE dbo.blob_versions SET published = 1");
        when(publish.executeUpdate()).thenReturn(1);
        return prepareChunkInsert();
    }

    private PreparedStatement prepareChunkInsert() throws SQLException {
        PreparedStatement insert = statement("INSERT INTO dbo.blob_chunks");
        AtomicReference<BinaryParameter> parameter = new AtomicReference<>();
        doAnswer(call -> {
            parameter.set(new BinaryParameter(call.getArgument(1), call.getArgument(2)));
            return null;
        }).when(insert).setBinaryStream(eq(3), any(InputStream.class), anyLong());
        when(insert.executeUpdate()).thenAnswer(call -> {
            BinaryParameter binary = parameter.get();
            try (InputStream row = binary.stream()) {
                assertThat(row.transferTo(OutputStream.nullOutputStream())).isEqualTo(binary.length());
            } catch (IOException e) {
                throw new SQLException("write failed while reading binary input", "08S01", e);
            }
            return 1;
        });
        return insert;
    }

    private ResultSet uploadState(ChunkedUpload upload, boolean published, boolean retired) throws SQLException {
        ResultSet state = resultOf(statement("upload_chunk_size IS NOT NULL"));
        when(state.next()).thenReturn(true);
        when(state.getLong(1)).thenReturn(upload.totalSize());
        when(state.getInt(2)).thenReturn(upload.chunkSize());
        when(state.getBoolean(3)).thenReturn(published);
        when(state.getBoolean(4)).thenReturn(retired);
        return state;
    }

    private void published(long size) throws SQLException {
        ResultSet result = resultOf(statement("AND published = 1"));
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn(VERSION);
        when(result.getLong(2)).thenReturn(size);
    }

    private static ResultSet row(long offset, int size, InputStream data) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.next()).thenReturn(true);
        when(row.getLong(1)).thenReturn(offset);
        when(row.getInt(2)).thenReturn(size);
        when(row.getBinaryStream(3)).thenReturn(data);
        return row;
    }

    private PreparedStatement statement(String sql) throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains(sql))).thenReturn(statement);
        return statement;
    }

    private static ResultSet resultOf(PreparedStatement statement) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(statement.executeQuery()).thenReturn(result);
        return result;
    }
}
