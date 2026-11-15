package ro.filegrid.ingest;

import java.io.ByteArrayInputStream;
import java.sql.SQLException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.storage.db.MeterReadingBatch;
import ro.filegrid.storage.db.MeterReadingRepository;
import ro.filegrid.storage.db.MeterReadingRepository.BeginResult;

/**
 * Reading batches, {@code POST /api/v1/meter-readings} (C7): deduplicate with {@link MeterReadingRepository#tryBegin}
 * before writing the file, then store the raw body as a {@code METER_READING} file through the policy, insert the
 * {@code files} row and {@code attachFile} in one transaction. On failure: {@code delete(attemptId)}, delete the
 * storage object and return 503 with {@code Retry-After}.
 */
@Service
@Profile("db")
public class MeterReadingIngestionService {

    static final String CONTENT_TYPE = "application/json";
    private static final int DEADLOCK_RETRIES = 3;
    private static final int SQL_SERVER_DEADLOCK = 1205;
    private static final Logger log = LoggerFactory.getLogger(MeterReadingIngestionService.class);

    private final MeterReadingRepository readings;
    private final FileMetadataRepository files;
    private final ContentWriter writer;
    private final TransactionTemplate transaction;
    private final Clock clock;

    private final ro.filegrid.observability.OperationMetrics metrics;

    @Autowired
    public MeterReadingIngestionService(MeterReadingRepository readings, FileMetadataRepository files,
                                        ContentWriter writer, PlatformTransactionManager transactions,
                                        ro.filegrid.observability.OperationMetrics metrics) {
        this(readings, files, writer, new TransactionTemplate(transactions), Clock.systemUTC(), metrics);
    }

    public MeterReadingIngestionService(MeterReadingRepository readings, FileMetadataRepository files,
                                        ContentWriter writer, TransactionTemplate transaction, Clock clock) {
        this(readings, files, writer, transaction, clock, ro.filegrid.observability.OperationMetrics.noop());
    }

    public MeterReadingIngestionService(MeterReadingRepository readings, FileMetadataRepository files,
                                        ContentWriter writer, TransactionTemplate transaction, Clock clock,
                                        ro.filegrid.observability.OperationMetrics metrics) {
        this.metrics = metrics;
        this.readings = readings;
        this.files = files;
        this.writer = writer;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** {@code body} is the raw body, already capped at the policy size and validated as {@code batch}. */
    public Result ingest(byte[] body, MeterReadingBatch batch, String createdBy) {
        BeginResult begin;
        try {
            begin = readings.tryBegin(batch);
        } catch (DataAccessException | TransactionException e) {
            log.warn("tryBegin failed for a batch of {} readings: {}", batch.readings().size(), e.getMessage());
            throw metadataUnavailable();
        }
        return switch (begin.status()) {
            case DUPLICATE_CONFIRMED -> duplicate(batch, begin.fileId());
            case DUPLICATE_PENDING -> throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BATCH_IN_PROGRESS",
                    "Lotul este deja în curs de procesare; reîncearcă cu aceeași cheie.",
                    IngestErrors.BATCH_RETRY_AFTER_SECONDS, null);
            case CONFLICT -> throw new ApiException(HttpStatus.CONFLICT, "BATCH_OVERLAP",
                    "Lotul se suprapune parțial cu un lot existent pentru același contor.");
            case NEW -> store(body, batch, begin.attemptId(), createdBy);
        };
    }

    private Result store(byte[] body, MeterReadingBatch batch, UUID attemptId, String createdBy) {
        ContentWriter.Stored stored;
        try {
            stored = writer.write(Category.METER_READING, body.length, CONTENT_TYPE,
                    () -> new ByteArrayInputStream(body), null);
        } catch (RuntimeException e) {
            compensate(attemptId, null);
            throw e instanceof ApiException api ? api : IngestErrors.storageUnavailable(
                    "Storage-ul pentru citiri este indisponibil; reîncearcă cu aceeași cheie.");
        }
        UUID fileId = UUID.randomUUID();
        FileRecord record = new FileRecord(fileId, Category.METER_READING, fileName(batch), CONTENT_TYPE,
                body.length, stored.sha256(), null, stored.storage(), stored.key(), FileRecord.CONFIRMED, false,
                clock.instant().truncatedTo(ChronoUnit.MICROS), createdBy, null);
        int expected = batch.readings().size();
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    transaction.executeWithoutResult(status -> {
                        files.insert(record);
                        int attached = readings.attachFile(attemptId, fileId);
                        if (attached != expected) {
                            throw new IllegalStateException("attachFile a legat " + attached + " din " + expected
                                    + " citiri");
                        }
                    });
                    break;
                } catch (RuntimeException e) {
                    if (attempt >= DEADLOCK_RETRIES || !isDeadlock(e)) {
                        throw e;
                    }
                    log.info("Deadlock while saving batch metadata (file {}), retry {}/{}", fileId,
                            attempt + 1, DEADLOCK_RETRIES);
                    backoff(attempt);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Batch metadata was not saved (file {}): {}", fileId, e.getMessage());
            compensate(attemptId, stored);
            throw metadataUnavailable();
        }
        return new Result(fileId, batch.meterId(), expected, Sha256.hex(stored.sha256()), false);
    }

    /** SQL Server deadlock: Spring locking exceptions, or an SQL cause with error code 1205. */
    static boolean isDeadlock(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CannotAcquireLockException || t instanceof DeadlockLoserDataAccessException
                    || t instanceof SQLException sql && sql.getErrorCode() == SQL_SERVER_DEADLOCK) {
                return true;
            }
        }
        return false;
    }

    private static void backoff(int attempt) {
        long ms = 15L * (attempt + 1) + ThreadLocalRandom.current().nextLong(0, 30);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private Result duplicate(MeterReadingBatch batch, UUID fileId) {
        metrics.duplicate(ro.filegrid.domain.Category.METER_READING);
        byte[] sha256;
        try {
            sha256 = files.findSha256(fileId).orElseThrow(() ->
                    new IllegalStateException("Fișierul confirmat " + fileId + " nu are rând în files"));
        } catch (DataAccessException e) {
            throw metadataUnavailable();
        }
        return new Result(fileId, batch.meterId(), batch.readings().size(), Sha256.hex(sha256), true);
    }

    private void compensate(UUID attemptId, ContentWriter.Stored stored) {
        if (stored != null) {
            writer.deleteQuietly(ro.filegrid.domain.Category.METER_READING, stored.storage(), stored.key());
        }
        try {
            readings.delete(attemptId);
        } catch (RuntimeException e) {
            // Rows left without file_id are picked up after 60 s and show up as an "unconfirmed reading".
            log.error("Compensation failed for attempt {}: {}", attemptId, e.getMessage());
        }
    }

    private static ApiException metadataUnavailable() {
        return IngestErrors.storageUnavailable("Baza de metadate este indisponibilă; reîncearcă cu aceeași cheie.");
    }

    private static String fileName(MeterReadingBatch batch) {
        return batch.meterId() + "_" + batch.readings().getFirst().ts() + ".json";
    }

    /** The contract's {@code MeterReadingResult} response. */
    public record Result(UUID fileId, String meterId, int readingsCount, String sha256, boolean duplicate) {
    }
}
