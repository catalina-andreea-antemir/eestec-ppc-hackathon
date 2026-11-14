package ro.filegrid.storage.db;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("db")
public class MeterReadingRepository {

    private static final int MAX_TIMESTAMP_PARAMETERS = 1000;
    private static final int MAX_BEGIN_TRIES = 3;

    private static final String INSERT_READING = """
            INSERT INTO dbo.meter_readings (meter_id, reading_ts, kwh, zone, attempt_id)
            VALUES (:meterId, :ts, :kwh, :zone, CAST(:attemptId AS UNIQUEIDENTIFIER))
            """;

    private static final String FIND_OVERLAP = """
            SELECT meter_id, reading_ts, kwh, zone, file_id, attempt_id
            FROM dbo.meter_readings WITH (UPDLOCK, HOLDLOCK)
            WHERE meter_id = :meterId AND reading_ts IN (:timestamps)
            """;

    private static final String COUNT_CONFIRMED_FILE_READINGS = """
            SELECT COUNT_BIG(*) FROM dbo.meter_readings WITH (HOLDLOCK)
            WHERE file_id = CAST(:fileId AS UNIQUEIDENTIFIER)
              AND EXISTS (SELECT 1 FROM dbo.files WITH (HOLDLOCK)
                          WHERE id = CAST(:fileId AS UNIQUEIDENTIFIER) AND status = N'CONFIRMED')
            """;

    private static final String COUNT_ATTEMPT_READINGS = """
            SELECT COUNT_BIG(*) FROM dbo.meter_readings WITH (HOLDLOCK)
            WHERE attempt_id = CAST(:attemptId AS UNIQUEIDENTIFIER)
            """;

    private static final String CLAIM_ATTEMPT = """
            UPDATE dbo.meter_readings
            SET attempt_id = CAST(:newAttemptId AS UNIQUEIDENTIFIER), received_at = SYSUTCDATETIME()
            WHERE attempt_id = CAST(:attemptId AS UNIQUEIDENTIFIER)
              AND file_id IS NULL
              AND received_at < DATEADD(second, -60, SYSUTCDATETIME())
            """;

    private static final String ATTACH_FILE = """
            UPDATE dbo.meter_readings
            SET file_id = CAST(:fileId AS UNIQUEIDENTIFIER)
            WHERE attempt_id = CAST(:attemptId AS UNIQUEIDENTIFIER)
              AND file_id IS NULL
              AND EXISTS (SELECT 1 FROM dbo.files
                          WHERE id = CAST(:fileId AS UNIQUEIDENTIFIER) AND status = N'CONFIRMED')
            """;

    private static final String DELETE_PENDING = """
            DELETE FROM dbo.meter_readings
            WHERE attempt_id = CAST(:attemptId AS UNIQUEIDENTIFIER) AND file_id IS NULL
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate reservationTransaction;

    public MeterReadingRepository(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.reservationTransaction = new TransactionTemplate(transactionManager);
        this.reservationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Reserves the whole batch before the caller writes its file to storage. */
    public BeginResult tryBegin(MeterReadingBatch batch) {
        Map<LocalDateTime, BigDecimal> readings = new TreeMap<>();
        for (MeterReadingBatch.Reading reading : batch.readings()) {
            readings.put(LocalDateTime.ofInstant(reading.ts(), ZoneOffset.UTC), reading.kwh());
        }
        UUID attemptId = UUID.randomUUID();
        for (int attempt = 1; attempt <= MAX_BEGIN_TRIES; attempt++) {
            try {
                BeginResult result = insertOrClassify(batch, readings, attemptId);
                if (result != null) {
                    return result;
                }
                // The previous owner compensated between the duplicate error and the lookup.
            } catch (DataAccessException exception) {
                // SQL Server rolled back this transaction after choosing it as the deadlock victim.
                if (attempt == MAX_BEGIN_TRIES || !hasSqlError(exception, 1205)) {
                    throw exception;
                }
            }
        }
        throw new ConcurrencyFailureException("Meter readings disappeared repeatedly during duplicate classification");
    }

    /** Returns the number of pending readings attached to an existing confirmed file. */
    public int attachFile(UUID attemptId, UUID fileId) {
        MapSqlParameterSource parameters = attemptParameter(attemptId)
                .addValue("fileId", fileId.toString(), Types.VARCHAR);
        return jdbc.update(ATTACH_FILE, parameters);
    }

    /** Returns the number of unconfirmed readings deleted for this attempt. */
    public int delete(UUID attemptId) {
        return jdbc.update(DELETE_PENDING, attemptParameter(attemptId));
    }

    private BeginResult insertOrClassify(MeterReadingBatch batch, Map<LocalDateTime, BigDecimal> readings,
                                         UUID attemptId) {
        SqlParameterSource[] parameters = readings.entrySet().stream()
                .map(entry -> attemptParameter(attemptId)
                        .addValue("meterId", batch.meterId(), Types.NVARCHAR)
                        .addValue("zone", batch.zone(), Types.NVARCHAR)
                        .addValue("ts", entry.getKey(), Types.TIMESTAMP)
                        .addValue("kwh", entry.getValue(), Types.DECIMAL))
                .toArray(SqlParameterSource[]::new);
        try {
            reservationTransaction.executeWithoutResult(status -> jdbc.batchUpdate(INSERT_READING, parameters));
            return new BeginResult(Status.NEW, attemptId, null);
        } catch (DataAccessException exception) {
            if (!hasSqlError(exception, 2627, 2601)) {
                throw exception;
            }
        }

        // The failed insert has rolled back. Hold locks through classification and any ownership change.
        return reservationTransaction.execute(transaction -> {
            List<StoredReading> overlap = findOverlap(batch.meterId(), readings);
            if (overlap.isEmpty()) {
                return null;
            }
            if (overlap.size() != readings.size() || !matches(batch, readings, overlap)) {
                return new BeginResult(Status.CONFLICT, null, null);
            }

            StoredReading first = overlap.getFirst();
            if (first.fileId() != null) {
                boolean sameFile = overlap.stream().allMatch(row -> first.fileId().equals(row.fileId()));
                if (sameFile && jdbc.queryForObject(COUNT_CONFIRMED_FILE_READINGS,
                        Map.of("fileId", first.fileId()), Long.class) == readings.size()) {
                    return new BeginResult(Status.DUPLICATE_CONFIRMED, null, UUID.fromString(first.fileId()));
                }
                return new BeginResult(Status.CONFLICT, null, null);
            }

            boolean samePendingAttempt = first.attemptId() != null && overlap.stream().allMatch(row ->
                    row.fileId() == null && first.attemptId().equals(row.attemptId()));
            if (!samePendingAttempt || jdbc.queryForObject(COUNT_ATTEMPT_READINGS,
                    Map.of("attemptId", first.attemptId()), Long.class) != readings.size()) {
                return new BeginResult(Status.CONFLICT, null, null);
            }

            int claimed = jdbc.update(CLAIM_ATTEMPT, attemptParameter(first.attemptId())
                    .addValue("newAttemptId", attemptId.toString(), Types.VARCHAR));
            if (claimed == readings.size()) {
                return new BeginResult(Status.NEW, attemptId, null);
            }
            // Rows near the expiry cutoff can have different received_at values.
            transaction.setRollbackOnly();
            return new BeginResult(Status.DUPLICATE_PENDING, null, null);
        });
    }

    private List<StoredReading> findOverlap(String meterId, Map<LocalDateTime, BigDecimal> readings) {
        List<LocalDateTime> timestamps = new ArrayList<>(readings.keySet());
        List<StoredReading> overlap = new ArrayList<>();
        for (int start = 0; start < timestamps.size(); start += MAX_TIMESTAMP_PARAMETERS) {
            int end = Math.min(start + MAX_TIMESTAMP_PARAMETERS, timestamps.size());
            var parameters = new MapSqlParameterSource()
                    .addValue("meterId", meterId, Types.NVARCHAR)
                    .addValue("timestamps", timestamps.subList(start, end), Types.TIMESTAMP);
            overlap.addAll(jdbc.query(FIND_OVERLAP, parameters, (row, index) -> new StoredReading(
                    row.getString("meter_id"), row.getObject("reading_ts", LocalDateTime.class),
                    row.getBigDecimal("kwh"), row.getString("zone"), row.getString("file_id"),
                    row.getString("attempt_id"))));
        }
        return overlap;
    }

    private static boolean matches(MeterReadingBatch batch, Map<LocalDateTime, BigDecimal> readings,
                                   List<StoredReading> overlap) {
        for (StoredReading row : overlap) {
            BigDecimal kwh = readings.get(row.ts());
            if (!batch.meterId().equals(row.meterId()) || !batch.zone().equals(row.zone())
                    || kwh == null || kwh.compareTo(row.kwh()) != 0) {
                return false;
            }
        }
        return true;
    }

    private static MapSqlParameterSource attemptParameter(UUID attemptId) {
        return attemptParameter(attemptId.toString());
    }

    private static MapSqlParameterSource attemptParameter(String attemptId) {
        return new MapSqlParameterSource().addValue("attemptId", attemptId, Types.VARCHAR);
    }

    private static boolean hasSqlError(Throwable failure, int... codes) {
        // JDBC batch failures can carry the server error in the next-exception chain.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                for (SQLException error = sqlException; error != null; error = error.getNextException()) {
                    for (int code : codes) {
                        if (error.getErrorCode() == code) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public enum Status {
        NEW, DUPLICATE_CONFIRMED, DUPLICATE_PENDING, CONFLICT
    }

    /** Supplies attemptId for NEW and fileId for DUPLICATE_CONFIRMED. */
    public record BeginResult(Status status, UUID attemptId, UUID fileId) {
        public BeginResult {
            Objects.requireNonNull(status, "status");
            if ((status == Status.NEW) != (attemptId != null)
                    || (status == Status.DUPLICATE_CONFIRMED) != (fileId != null)) {
                throw new IllegalArgumentException("Result identifiers must match the status");
            }
        }
    }

    private record StoredReading(String meterId, LocalDateTime ts, BigDecimal kwh, String zone,
                                 String fileId, String attemptId) {
    }
}
