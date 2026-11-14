package ro.filegrid.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.storage.db.MeterReadingBatch;
import ro.filegrid.storage.db.MeterReadingRepository;
import ro.filegrid.storage.db.MeterReadingRepository.BeginResult;
import ro.filegrid.storage.db.MeterReadingRepository.Status;

class MeterReadingIngestionServiceTest {

    private static final MeterReadingBatch BATCH = new MeterReadingBatch("RO-MUN-1", "Muntenia", List.of(
            new MeterReadingBatch.Reading(Instant.parse("2026-11-14T00:00:00Z"), new BigDecimal("0.412")),
            new MeterReadingBatch.Reading(Instant.parse("2026-11-14T00:15:00Z"), new BigDecimal("0.397"))));
    private static final byte[] BODY = "{\"meterId\":\"RO-MUN-1\"}".getBytes(StandardCharsets.UTF_8);
    private static final UUID ATTEMPT = UUID.randomUUID();

    private final IngestTestSupport env = new IngestTestSupport();
    private final MeterReadingRepository readings = mock(MeterReadingRepository.class);
    private final FileMetadataRepository files = mock(FileMetadataRepository.class);
    private final MeterReadingIngestionService service = new MeterReadingIngestionService(readings, files,
            env.writer, env.transaction, Clock.systemUTC());

    @Test
    void newBatchStoresRawBodyThenFileRowThenAttaches() {
        when(readings.tryBegin(BATCH)).thenReturn(new BeginResult(Status.NEW, ATTEMPT, null));
        when(readings.attachFile(eq(ATTEMPT), any())).thenReturn(2);

        MeterReadingIngestionService.Result result = service.ingest(BODY, BATCH, "contor");

        ArgumentCaptor<FileRecord> row = ArgumentCaptor.forClass(FileRecord.class);
        verify(files).insert(row.capture());
        verify(readings).attachFile(ATTEMPT, result.fileId());
        assertThat(row.getValue().category()).isEqualTo(Category.METER_READING);
        assertThat(row.getValue().storage()).isEqualTo(StorageType.DB);
        assertThat(row.getValue().size()).isEqualTo(BODY.length);
        assertThat(result.duplicate()).isFalse();
        assertThat(result.readingsCount()).isEqualTo(2);
        assertThat(result.sha256()).isEqualTo(Sha256.hex(Sha256.of(BODY)));
        assertThat(env.db.exists(row.getValue().storageKey())).isTrue();
    }

    @Test
    void confirmedDuplicateReturnsTheFirstFileWithoutWriting() {
        UUID fileId = UUID.randomUUID();
        when(readings.tryBegin(BATCH)).thenReturn(new BeginResult(Status.DUPLICATE_CONFIRMED, null, fileId));
        when(files.findSha256(fileId)).thenReturn(Optional.of(Sha256.of(BODY)));

        MeterReadingIngestionService.Result result = service.ingest(BODY, BATCH, "contor");

        assertThat(result.duplicate()).isTrue();
        assertThat(result.fileId()).isEqualTo(fileId);
        assertThat(env.db.puts.get() + env.s3.puts.get()).isZero();
    }

    @Test
    void pendingDuplicateIs503BatchInProgress() {
        when(readings.tryBegin(BATCH)).thenReturn(new BeginResult(Status.DUPLICATE_PENDING, null, null));

        assertError(503, "BATCH_IN_PROGRESS");
        assertThat(env.db.puts).hasValue(0);
    }

    @Test
    void conflictIs409BatchOverlap() {
        when(readings.tryBegin(BATCH)).thenReturn(new BeginResult(Status.CONFLICT, null, null));

        assertError(409, "BATCH_OVERLAP");
    }

    @Test
    void storageDownCompensatesTheReservation() {
        when(readings.tryBegin(BATCH)).thenReturn(new BeginResult(Status.NEW, ATTEMPT, null));
        env.db.setUp(false);
        env.s3.setUp(false);

        assertError(503, "STORAGE_UNAVAILABLE");
        verify(readings).delete(ATTEMPT);
        verify(files, never()).insert(any());
    }

    @Test
    void metadataFailureDeletesObjectAndReservation() {
        when(readings.tryBegin(BATCH)).thenReturn(new BeginResult(Status.NEW, ATTEMPT, null));
        doThrow(new DataAccessResourceFailureException("simulat")).when(files).insert(any());

        assertError(503, "STORAGE_UNAVAILABLE");
        assertThat(env.db.puts).hasValue(1);
        assertThat(env.db.deletes).hasValue(1);
        verify(readings).delete(ATTEMPT);
    }

    @Test
    void partialAttachRollsBackAndCompensates() {
        when(readings.tryBegin(BATCH)).thenReturn(new BeginResult(Status.NEW, ATTEMPT, null));
        when(readings.attachFile(eq(ATTEMPT), any())).thenReturn(1);

        assertError(503, "STORAGE_UNAVAILABLE");
        assertThat(env.db.deletes).hasValue(1);
        verify(readings).delete(ATTEMPT);
    }

    @Test
    void metadataDatabaseDownAtBeginIs503() {
        when(readings.tryBegin(BATCH)).thenThrow(new DataAccessResourceFailureException("simulat"));

        assertError(503, "STORAGE_UNAVAILABLE");
    }

    private void assertError(int status, String code) {
        assertThatThrownBy(() -> service.ingest(BODY, BATCH, "contor"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(status);
                    assertThat(e.code()).isEqualTo(code);
                    if (status == 503) {
                        assertThat(e.retryAfterSeconds()).isPositive();
                    }
                });
    }
}
