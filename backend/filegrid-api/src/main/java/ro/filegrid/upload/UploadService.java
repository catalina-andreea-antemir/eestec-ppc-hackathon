package ro.filegrid.upload;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.ingest.FileMetadataRepository;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.ingest.IngestErrors;
import ro.filegrid.ingest.StorageSelector;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.policy.CategoryPolicy;
import ro.filegrid.policy.PolicyEngine;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.ChunkReceipt;
import ro.filegrid.storage.ChunkedUpload;
import ro.filegrid.storage.ChunkedWriter;
import ro.filegrid.observability.OperationMetrics;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageAdapterRegistry;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageUnavailableException;

/**
 * Resumable chunked upload (C7, ADR-003). All state lives in FileGridMeta ({@code upload_sessions},
 * {@code upload_chunks}); any instance can continue an upload. Chunks are written under a per-upload lease taken
 * with an atomic {@code UPDATE}; strictly sequential; the last chunk, if resent, is confirmed idempotently.
 */
@Service
@Profile("db")
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final int HASH_BUFFER = 64 * 1024;
    private static final int CLEANUP_BATCH = 100;

    private final PolicyEngine policies;
    private final StorageSelector selector;
    private final StorageAdapterRegistry registry;
    private final UploadSessionRepository sessions;
    private final FileMetadataRepository files;
    private final TransactionTemplate transaction;
    private final UploadSettings settings;
    private final Clock clock;
    private final OperationMetrics metrics;

    @Autowired
    public UploadService(PolicyEngine policies, StorageSelector selector, StorageAdapterRegistry registry,
                         UploadSessionRepository sessions, FileMetadataRepository files,
                         PlatformTransactionManager transactions,
                         @Value("${filegrid.uploads.lease-ttl:2m}") Duration leaseTtl,
                         @Value("${filegrid.uploads.inactive-ttl:24h}") Duration inactiveTtl,
                         @Value("${filegrid.uploads.storage-down-ttl:10m}") Duration storageDownTtl,
                         OperationMetrics metrics) {
        this(policies, selector, registry, sessions, files, new TransactionTemplate(transactions),
                new UploadSettings(leaseTtl, inactiveTtl, storageDownTtl), Clock.systemUTC(), metrics);
    }

    public UploadService(PolicyEngine policies, StorageSelector selector, StorageAdapterRegistry registry,
                         UploadSessionRepository sessions, FileMetadataRepository files,
                         TransactionTemplate transaction, UploadSettings settings, Clock clock) {
        this(policies, selector, registry, sessions, files, transaction, settings, clock, OperationMetrics.noop());
    }

    public UploadService(PolicyEngine policies, StorageSelector selector, StorageAdapterRegistry registry,
                         UploadSessionRepository sessions, FileMetadataRepository files,
                         TransactionTemplate transaction, UploadSettings settings, Clock clock,
                         OperationMetrics metrics) {
        this.metrics = metrics;
        this.policies = policies;
        this.selector = selector;
        this.registry = registry;
        this.sessions = sessions;
        this.files = files;
        this.transaction = transaction;
        this.settings = settings;
        this.clock = clock;
    }

    /** Who is calling: the token's username and whether it is ADMIN. */
    public record Caller(String name, boolean admin) {
    }

    /** Result of {@code complete}: the file and whether it was just created (201) or already existed (200). */
    public record Completed(FileRecord file, boolean created) {
    }

    // --- init ------------------------------------------------------------------------------------------------

    public UploadStatus init(Category category, String fileName, long totalSize, Caller caller) {
        CategoryPolicy policy = policies.policy(category);
        int chunkSize = policy.chunkSize().orElseThrow(() -> IngestErrors.validation("category",
                "Categoria " + category + " nu are upload în bucăți (lipsește chunkSize în politică)."));
        if (totalSize <= 0) {
            throw IngestErrors.validation("totalSize", "Câmpul „totalSize” trebuie să fie pozitiv.");
        }
        if (totalSize > policy.maxSizeBytes()) {
            throw IngestErrors.tooLarge(category, policy.maxSizeBytes());
        }
        UUID id = UUID.randomUUID();
        String key = id.toString();
        for (StorageAdapter candidate : selector.candidates(category)) {
            String ref;
            ChunkedWriter writer;
            try {
                writer = registry.chunkedWriter(candidate.type());
                ref = writer.open(key, totalSize, chunkSize);
            } catch (StorageException e) {
                selector.markDown(candidate.type());
                log.warn("Cannot open an upload on {}: {}", candidate.type(), e.getMessage(), e);
                continue;
            }
            UploadSession session = new UploadSession(id, category, candidate.type(), ref, fileName,
                    contentType(fileName), chunkSize, totalSize, UploadSession.PENDING, 1, now(), now(), null,
                    caller.name());
            try {
                sessions.insert(session);
            } catch (DataAccessException e) {
                quietlyAbort(writer, session.toChunkedUpload());
                throw IngestErrors.storageUnavailable("Baza de metadate este indisponibilă; upload-ul nu a început.");
            }
            return UploadStatus.of(session, 0);
        }
        throw IngestErrors.storageUnavailable("Niciun storage pentru " + category
                + " (principal sau de rezervă) nu este disponibil; reîncearcă.");
    }

    // --- status ----------------------------------------------------------------------------------------------

    public UploadStatus get(UUID id, Caller caller) {
        UploadSession session = authorized(id, caller);
        return UploadStatus.of(session, receivedBytes(session));
    }

    // --- chunks ----------------------------------------------------------------------------------------------

    /**
     * Writes chunk {@code n} from {@code body} (closed by the caller). {@code contentLength} is {@code -1} if unknown.
     */
    public ChunkAck putChunk(UUID id, int n, Caller caller, InputStream body, long contentLength) {
        UploadSession session = authorized(id, caller);
        requireActive(session);
        if (n < 1 || n > session.totalChunks()) {
            throw IngestErrors.validation("n", "Bucata " + n + " nu există (1–" + session.totalChunks() + ").");
        }
        String owner = newOwner();
        acquire(id, owner);
        try {
            session = find(id);
            requireActive(session);
            if (n == session.nextChunk() - 1) {
                return duplicate(session, n);
            }
            if (n != session.nextChunk()) {
                throw UploadErrors.outOfOrder(session.nextChunk(), n);
            }
            ChunkedUpload upload = session.toChunkedUpload();
            long expected = upload.expectedLength(n);
            if (contentLength >= 0 && contentLength != expected) {
                throw IngestErrors.validation("body", "Bucata " + n + " trebuie să aibă exact " + expected
                        + " octeți (Content-Length: " + contentLength + ").");
            }
            return write(session, upload, n, expected, owner, body);
        } finally {
            releaseQuietly(id, owner);
        }
    }

    private ChunkAck write(UploadSession session, ChunkedUpload upload, int n, long expected, String owner,
                           InputStream body) {
        BodyStream in = new BodyStream(body, expected, () -> renewQuietly(session.id(), owner));
        ChunkReceipt receipt;
        try {
            receipt = metrics.time(session.category(), session.storage(), OperationMetrics.UPLOAD_CHUNK, expected,
                    () -> writer(session).append(upload, n, in, expected));
        } catch (StorageException e) {
            if (in.sourceFailed()) {
                throw IngestErrors.validation("body", "Corpul bucății " + n + " este incomplet sau întrerupt ("
                        + in.bytesRead() + " din " + expected + " octeți).");
            }
            throw storageFailure(session, e);
        }
        try {
            if (in.hasExtra()) {
                throw IngestErrors.validation("body", "Bucata " + n + " are mai mult de " + expected + " octeți.");
            }
        } catch (IOException e) {
            throw IngestErrors.validation("body", "Corpul bucății " + n + " nu a putut fi citit până la capăt.");
        }
        if (receipt.size() != expected) {
            throw storageFailure(session, new StorageException(session.storage(),
                    "Storage-ul a confirmat " + receipt.size() + " octeți în loc de " + expected));
        }
        byte[] sha = in.sha256();
        boolean recorded;
        try {
            recorded = Boolean.TRUE.equals(transaction.execute(status ->
                    sessions.recordChunk(session.id(), owner, receipt, sha)));
        } catch (DataAccessException e) {
            throw IngestErrors.storageUnavailable("Baza de metadate este indisponibilă; reîncearcă bucata " + n + ".");
        }
        if (!recorded) {
            throw UploadErrors.busy();
        }
        return new ChunkAck(n, receipt.size(), Sha256.hex(sha), n + 1, false);
    }

    private ChunkAck duplicate(UploadSession session, int n) {
        UploadSessionRepository.Chunk chunk = sessions.findChunk(session.id(), n)
                .orElseThrow(() -> UploadErrors.outOfOrder(session.nextChunk(), n));
        return new ChunkAck(n, chunk.size(), Sha256.hex(chunk.sha256()), session.nextChunk(), true);
    }

    // --- complete --------------------------------------------------------------------------------------------

    public Completed complete(UUID id, Caller caller) {
        UploadSession session = authorized(id, caller);
        if (UploadSession.COMPLETED.equals(session.status())) {
            return existing(session);
        }
        requireActive(session);
        String owner = newOwner();
        acquire(id, owner);
        try {
            session = find(id);
            if (UploadSession.COMPLETED.equals(session.status())) {
                return existing(session);
            }
            requireActive(session);
            List<UploadSessionRepository.Chunk> chunks = sessions.chunks(id);
            int total = session.totalChunks();
            long received = chunks.stream().mapToLong(UploadSessionRepository.Chunk::size).sum();
            if (session.nextChunk() != total + 1 || chunks.size() != total || received != session.totalSize()) {
                throw UploadErrors.incomplete(session.nextChunk(), total);
            }
            ChunkedUpload upload = session.toChunkedUpload();
            UploadSession current = session;
            try {
                metrics.run(current.category(), current.storage(), OperationMetrics.UPLOAD_COMPLETE,
                        current.totalSize(), () -> writer(current).finish(upload,
                                chunks.stream().map(UploadSessionRepository.Chunk::receipt).toList()));
            } catch (StorageException e) {
                throw storageFailure(session, e);
            }
            byte[] hash = hashStored(session, owner);
            FileRecord record = new FileRecord(session.id(), session.category(), session.fileName(),
                    session.contentType(), session.totalSize(), hash, null, session.storage(), session.id().toString(),
                    FileRecord.CONFIRMED, false, now(), session.createdBy(), null);
            try {
                transaction.executeWithoutResult(status -> {
                    files.insert(record);
                    sessions.finish(record.id(), UploadSession.COMPLETED);
                });
            } catch (DataAccessException e) {
                // The session stays PENDING and the object stays in storage: a new complete resumes from finish (idempotent).
                log.error("Metadata for upload {} was not saved: {}", id, e.getMessage());
                throw IngestErrors.storageUnavailable("Baza de metadate este indisponibilă; reîncearcă complete.");
            }
            return new Completed(record, true);
        } finally {
            releaseQuietly(id, owner);
        }
    }

    private Completed existing(UploadSession session) {
        FileRecord file = files.find(session.id()).orElseThrow(() -> new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Sesiunea e finalizată, dar rândul din files lipsește."));
        return new Completed(file, false);
    }

    /** SHA-256 of the final object, streamed from storage (never held in memory). */
    private byte[] hashStored(UploadSession session, String owner) {
        StorageAdapter adapter = registry.adapter(session.storage());
        long lastRenew = System.nanoTime();
        try (InputStream raw = adapter.get(session.id().toString(), ByteRange.full())) {
            DigestInputStream in = Sha256.digesting(raw);
            byte[] buffer = new byte[HASH_BUFFER];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (System.nanoTime() - lastRenew > 15_000_000_000L) {
                    lastRenew = System.nanoTime();
                    renewQuietly(session.id(), owner);
                }
            }
            if (total != session.totalSize()) {
                throw storageFailure(session, new StorageException(session.storage(),
                        "Obiectul din storage are " + total + " octeți, se așteptau " + session.totalSize()));
            }
            return in.getMessageDigest().digest();
        } catch (StorageException e) {
            throw storageFailure(session, e);
        } catch (IOException e) {
            throw storageFailure(session, new StorageUnavailableException(session.storage(),
                    "Citirea obiectului pentru hash a eșuat"));
        }
    }

    // --- abort and cleanup -----------------------------------------------------------------------------------

    public UploadStatus abort(UUID id, Caller caller) {
        UploadSession session = authorized(id, caller);
        if (UploadSession.ABORTED.equals(session.status()) || UploadSession.FAILED.equals(session.status())) {
            return UploadStatus.of(session, receivedBytes(session));
        }
        requireActive(session);
        String owner = newOwner();
        acquire(id, owner);
        try {
            session = find(id);
            requireActive(session);
            abandon(session, UploadSession.ABORTED);
            return get(id, caller);
        } finally {
            releaseQuietly(id, owner);
        }
    }

    /**
     * Abandons sessions idle beyond the TTL or with storage down beyond the outage TTL (scheduled job).
     *
     * @return how many sessions were abandoned
     */
    public int cleanup() {
        int done = 0;
        for (UUID id : sessions.abandonable(settings.inactiveTtl().toSeconds(),
                settings.storageDownTtl().toSeconds(), CLEANUP_BATCH)) {
            String owner = "cleanup-" + UUID.randomUUID();
            if (!sessions.tryAcquireLease(id, owner, leaseSeconds())) {
                continue;
            }
            try {
                UploadSession session = find(id);
                if (session.pending()) {
                    abandon(session, UploadSession.FAILED);
                    done++;
                }
            } catch (RuntimeException e) {
                log.warn("Cleanup of session {} failed: {}", id, e.getMessage());
            } finally {
                releaseQuietly(id, owner);
            }
        }
        return done;
    }

    /** Abort in storage (at most one attempt; on failure leftovers remain and are logged) and final state in the DB. */
    private void abandon(UploadSession session, String status) {
        try {
            metrics.run(session.category(), session.storage(), OperationMetrics.DELETE, null,
                    () -> writer(session).abort(session.toChunkedUpload()));
        } catch (RuntimeException e) {
            log.warn("Abort of upload {} on {} failed; leftovers may remain: {}", session.id(),
                    session.storage(), e.getMessage());
        }
        sessions.finish(session.id(), status);
        log.info("Upload {} ({}) closed with status {}", session.id(), session.storage(), status);
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    /** The session's category from its row (after the owner check); used by the bulkhead (C9) before the heavy work. */
    public Category categoryOf(UUID id, Caller caller) {
        return authorized(id, caller).category();
    }

    private UploadSession authorized(UUID id, Caller caller) {
        UploadSession session = find(id);
        if (!caller.admin() && !session.createdBy().equals(caller.name())) {
            throw UploadErrors.forbidden();
        }
        return session;
    }

    private UploadSession find(UUID id) {
        try {
            return sessions.find(id).orElseThrow(UploadErrors::notFound);
        } catch (DataAccessException e) {
            throw IngestErrors.storageUnavailable("Baza de metadate este indisponibilă; reîncearcă.");
        }
    }

    private void requireActive(UploadSession session) {
        if (!session.pending()) {
            throw UploadErrors.notActive(session.apiStatus());
        }
    }

    private void acquire(UUID id, String owner) {
        boolean acquired;
        try {
            acquired = sessions.tryAcquireLease(id, owner, leaseSeconds());
        } catch (DataAccessException e) {
            throw IngestErrors.storageUnavailable("Baza de metadate este indisponibilă; reîncearcă.");
        }
        if (!acquired) {
            requireActive(find(id));
            throw UploadErrors.busy();
        }
    }

    private ChunkedWriter writer(UploadSession session) {
        try {
            return registry.chunkedWriter(session.storage());
        } catch (StorageException e) {
            throw storageFailure(session, e);
        }
    }

    /** 503 + Retry-After; the session stays bound to its storage and the first outage time is recorded for abandonment. */
    private ApiException storageFailure(UploadSession session, StorageException e) {
        log.warn("Storage {} of upload {} did not respond: {}", session.storage(), session.id(), e.getMessage());
        if (e instanceof StorageUnavailableException) {
            selector.markDown(session.storage());
            try {
                sessions.markStorageUnavailable(session.id());
            } catch (DataAccessException ignored) {
                // Without an outage timestamp the session is only abandoned later (after 24 h).
            }
        }
        return IngestErrors.storageUnavailable("Storage-ul " + session.storage()
                + " al acestui upload nu este disponibil; reia bucata după revenire.");
    }

    private long receivedBytes(UploadSession session) {
        try {
            return sessions.receivedBytes(session.id());
        } catch (DataAccessException e) {
            throw IngestErrors.storageUnavailable("Baza de metadate este indisponibilă; reîncearcă.");
        }
    }

    private void quietlyAbort(ChunkedWriter writer, ChunkedUpload upload) {
        try {
            writer.abort(upload);
        } catch (RuntimeException e) {
            log.warn("Abort after metadata failure: {}", e.getMessage());
        }
    }

    private void releaseQuietly(UUID id, String owner) {
        try {
            sessions.releaseLease(id, owner);
        } catch (RuntimeException e) {
            log.warn("Cannot release the lease of upload {} (it expires on its own): {}", id, e.getMessage());
        }
    }

    private void renewQuietly(UUID id, String owner) {
        try {
            sessions.renewLease(id, owner, leaseSeconds());
        } catch (RuntimeException e) {
            log.warn("Cannot extend the lease of upload {}: {}", id, e.getMessage());
        }
    }

    private int leaseSeconds() {
        return (int) settings.leaseTtl().toSeconds();
    }

    private static String newOwner() {
        return "req-" + UUID.randomUUID();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static String contentType(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp4")) {
            return "video/mp4";
        } else if (name.endsWith(".mov")) {
            return "video/quicktime";
        } else if (name.endsWith(".mkv")) {
            return "video/x-matroska";
        } else if (name.endsWith(".webm")) {
            return "video/webm";
        } else if (name.endsWith(".avi")) {
            return "video/x-msvideo";
        }
        return "application/octet-stream";
    }

    /** Durations (lease, idle, storage outage); defaults 2 min, 24 h, 10 min. */
    public record UploadSettings(Duration leaseTtl, Duration inactiveTtl, Duration storageDownTtl) {
    }
}
