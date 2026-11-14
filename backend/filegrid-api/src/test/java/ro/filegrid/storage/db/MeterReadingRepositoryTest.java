package ro.filegrid.storage.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.BatchUpdateException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ro.filegrid.storage.db.MeterReadingBatch.Reading;
import ro.filegrid.storage.db.MeterReadingRepository.BeginResult;
import ro.filegrid.storage.db.MeterReadingRepository.Status;

class MeterReadingRepositoryTest {

    private static final Instant FIRST_TS = Instant.parse("2026-11-14T00:00:00.1234567Z");
    private static final Instant SECOND_TS = FIRST_TS.plusSeconds(900);
    private static final UUID FILE_ID = UUID.randomUUID();
    private static final UUID OLD_ATTEMPT_ID = UUID.randomUUID();
    private static final MeterReadingBatch BATCH = new MeterReadingBatch("meter-1", "Muntenia", List.of(
            new Reading(FIRST_TS, new BigDecimal("0.412")), new Reading(SECOND_TS, new BigDecimal("0.397"))));

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MeterReadingRepository repository = new MeterReadingRepository(jdbc, transactions);

    @BeforeEach
    void prepareTransactions() {
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    }

    @Test
    void insertsBeforeReadingAndReturnsAnAttemptOnlyAfterCommit() {
        BeginResult result = repository.tryBegin(BATCH);

        assertThat(result.status()).isEqualTo(Status.NEW);
        assertThat(result.attemptId()).isNotNull();
        var order = inOrder(jdbc, transactions);
        order.verify(transactions).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        order.verify(jdbc).batchUpdate(contains("INSERT INTO dbo.meter_readings"),
                argThat((SqlParameterSource[] rows) -> rows.length == 2
                        && rows[0].getValue("attemptId").equals(result.attemptId().toString())
                        && rows[0].getValue("ts").equals(LocalDateTime.ofInstant(FIRST_TS, ZoneOffset.UTC))));
        order.verify(transactions).commit(any());
        order.verifyNoMoreInteractions();
    }

    @Test
    void rollsBackTheFailedInsertBeforeReturningTheOriginalFile() throws SQLException {
        failInsertWith(2627);
        overlap(confirmed(FIRST_TS, "0.412000"), confirmed(SECOND_TS, "0.397"));
        when(jdbc.queryForObject(contains("status = N'CONFIRMED'"), eq(Map.of("fileId", FILE_ID.toString())),
                eq(Long.class))).thenReturn(2L);

        assertThat(repository.tryBegin(BATCH)).isEqualTo(new BeginResult(Status.DUPLICATE_CONFIRMED, null, FILE_ID));
        var order = inOrder(jdbc, transactions);
        order.verify(jdbc).batchUpdate(anyString(), any(SqlParameterSource[].class));
        order.verify(transactions).rollback(any());
        order.verify(transactions).getTransaction(any());
        order.verify(jdbc).query(contains("UPDLOCK"), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Object>>any());
    }

    @Test
    void rejectsPartiallyOverlappingTimestamps() throws SQLException {
        failInsertWith(2601);
        overlap(confirmed(FIRST_TS, "0.412"));

        assertThat(repository.tryBegin(BATCH).status()).isEqualTo(Status.CONFLICT);
    }

    @Test
    void rejectsDifferentKwhEvenWithTheSameTimestamps() throws SQLException {
        failInsertWith(2627);
        overlap(confirmed(FIRST_TS, "0.411"), confirmed(SECOND_TS, "0.397"));

        assertThat(repository.tryBegin(BATCH).status()).isEqualTo(Status.CONFLICT);
    }

    @Test
    void rejectsDifferentZoneEvenWithTheSameReadings() throws SQLException {
        failInsertWith(2627);
        ResultSet first = confirmed(FIRST_TS, "0.412");
        when(first.getString("zone")).thenReturn("Banat");
        overlap(first, confirmed(SECOND_TS, "0.397"));

        assertThat(repository.tryBegin(BATCH).status()).isEqualTo(Status.CONFLICT);
    }

    @Test
    void rejectsStrictSubsetOfAConfirmedFile() throws SQLException {
        failInsertWith(2627);
        overlap(confirmed(FIRST_TS, "0.412"), confirmed(SECOND_TS, "0.397"));
        when(jdbc.queryForObject(contains("WHERE file_id"), anyMap(), eq(Long.class))).thenReturn(3L);

        assertThat(repository.tryBegin(BATCH).status()).isEqualTo(Status.CONFLICT);
    }

    @Test
    void rejectsStrictSubsetOfAPendingAttemptWithoutChangingItsOwner() throws SQLException {
        pendingOverlap(3L);

        assertThat(repository.tryBegin(BATCH).status()).isEqualTo(Status.CONFLICT);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void commitsTheClaimWhenAllRowsWereUpdated() throws SQLException {
        pendingOverlap(2L);
        when(jdbc.update(contains("DATEADD(second, -60, SYSUTCDATETIME())"), any(SqlParameterSource.class)))
                .thenReturn(2);

        BeginResult result = repository.tryBegin(BATCH);

        assertThat(result.status()).isEqualTo(Status.NEW);
        assertThat(result.attemptId()).isNotNull().isNotEqualTo(OLD_ATTEMPT_ID);
        verify(jdbc).update(contains("SET attempt_id"), argThat((SqlParameterSource params) ->
                params.getValue("attemptId").equals(OLD_ATTEMPT_ID.toString())
                        && params.getValue("newAttemptId").equals(result.attemptId().toString())));
        verify(transactions).commit(argThat(status -> !status.isRollbackOnly()));
    }

    @Test
    void returnsPendingWithoutChangingTheOwnerOfANonExpiredAttempt() throws SQLException {
        pendingOverlap(2L);
        String expiryCondition = "received_at < DATEADD(second, -60, SYSUTCDATETIME())";
        when(jdbc.update(contains(expiryCondition), any(SqlParameterSource.class))).thenReturn(0);

        assertThat(repository.tryBegin(BATCH)).isEqualTo(new BeginResult(Status.DUPLICATE_PENDING, null, null));
        verify(jdbc).update(contains(expiryCondition), argThat((SqlParameterSource params) ->
                OLD_ATTEMPT_ID.toString().equals(params.getValue("attemptId"))));
        verify(transactions).commit(argThat(TransactionStatus::isRollbackOnly));
    }

    @Test
    void marksAPartialClaimForRollbackAndReturnsPending() throws SQLException {
        pendingOverlap(2L);
        when(jdbc.update(contains("SET attempt_id"), any(SqlParameterSource.class))).thenReturn(1);

        assertThat(repository.tryBegin(BATCH)).isEqualTo(new BeginResult(Status.DUPLICATE_PENDING, null, null));
        verify(transactions).commit(argThat(TransactionStatus::isRollbackOnly));
    }

    @Test
    void triesTheInsertAgainIfCompensationRemovedTheEntireOverlap() {
        when(jdbc.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                .thenThrow(insertError(2627)).thenReturn(new int[] {1, 1});
        overlap();

        assertThat(repository.tryBegin(BATCH).status()).isEqualTo(Status.NEW);
        verify(jdbc, times(2)).batchUpdate(anyString(), any(SqlParameterSource[].class));
    }

    @Test
    void propagatesAnUnrelatedInsertError() {
        DataIntegrityViolationException failure = insertError(547);
        when(jdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenThrow(failure);

        assertThatThrownBy(() -> repository.tryBegin(BATCH)).isSameAs(failure);
        verify(transactions).rollback(any());
    }

    @Test
    void attachesAndDeletesOnlyPendingRowsForTheSuppliedAttempt() {
        assertThat(repository.attachFile(OLD_ATTEMPT_ID, FILE_ID)).isZero();
        assertThat(repository.delete(OLD_ATTEMPT_ID)).isZero();

        verify(jdbc).update(
                contains("WHERE attempt_id = CAST(:attemptId AS UNIQUEIDENTIFIER)\n  AND file_id IS NULL"),
                argThat((SqlParameterSource params) -> params.getValue("fileId").equals(FILE_ID.toString())
                        && params.getValue("attemptId").equals(OLD_ATTEMPT_ID.toString())));
        verify(jdbc).update(
                contains("WHERE attempt_id = CAST(:attemptId AS UNIQUEIDENTIFIER) AND file_id IS NULL"),
                argThat((SqlParameterSource params) -> params.getValue("attemptId").equals(OLD_ATTEMPT_ID.toString())));
    }

    private void pendingOverlap(long attemptSize) throws SQLException {
        failInsertWith(2627);
        overlap(row(FIRST_TS, "0.412", null), row(SECOND_TS, "0.397", null));
        when(jdbc.queryForObject(contains("WHERE attempt_id"), anyMap(), eq(Long.class))).thenReturn(attemptSize);
    }

    private void failInsertWith(int code) {
        when(jdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenThrow(insertError(code));
    }

    private static DataIntegrityViolationException insertError(int code) {
        BatchUpdateException batchError = new BatchUpdateException();
        batchError.setNextException(new SQLException("Insert failed", null, code));
        return new DataIntegrityViolationException("Batch insert failed", batchError);
    }

    private void overlap(ResultSet... rows) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any()))
                .thenAnswer(call -> {
                    RowMapper<?> mapper = call.getArgument(2);
                    List<Object> result = new ArrayList<>();
                    for (int index = 0; index < rows.length; index++) {
                        result.add(mapper.mapRow(rows[index], index));
                    }
                    return result;
                });
    }

    private static ResultSet confirmed(Instant ts, String kwh) throws SQLException {
        return row(ts, kwh, FILE_ID);
    }

    private static ResultSet row(Instant ts, String kwh, UUID fileId) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("meter_id")).thenReturn("meter-1");
        when(row.getObject("reading_ts", LocalDateTime.class)).thenReturn(LocalDateTime.ofInstant(ts, ZoneOffset.UTC));
        when(row.getBigDecimal("kwh")).thenReturn(new BigDecimal(kwh));
        when(row.getString("zone")).thenReturn("Muntenia");
        when(row.getString("file_id")).thenReturn(fileId == null ? null : fileId.toString());
        when(row.getString("attempt_id")).thenReturn(OLD_ATTEMPT_ID.toString());
        return row;
    }
}
