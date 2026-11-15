package ro.filegrid.ingest;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ro.filegrid.domain.Category;
import ro.filegrid.policy.CategoryPolicy;
import ro.filegrid.policy.PolicyEngine;

/**
 * Upload-ul de fișiere mici, {@code POST /api/v1/files} (C7): verifică mărimea din politică, scrie în storage
 * (hash în streaming), abia apoi înregistrează {@code files} (CONFIRMED) și {@code outage_records} într-o
 * tranzacție. Dacă metadatele eșuează, obiectul din storage se șterge (compensare) și răspunsul e 503.
 */
@Service
@Profile("db")
public class FileIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FileIngestionService.class);

    private final PolicyEngine policies;
    private final ContentWriter writer;
    private final FileMetadataRepository files;
    private final TransactionTemplate transaction;
    private final DocumentEncryption encryption;
    private final Clock clock;

    @Autowired
    public FileIngestionService(PolicyEngine policies, ContentWriter writer, FileMetadataRepository files,
                                PlatformTransactionManager transactions, DocumentEncryption encryption) {
        this(policies, writer, files, new TransactionTemplate(transactions), encryption, Clock.systemUTC());
    }

    public FileIngestionService(PolicyEngine policies, ContentWriter writer, FileMetadataRepository files,
                                TransactionTemplate transaction, DocumentEncryption encryption, Clock clock) {
        this.policies = policies;
        this.writer = writer;
        this.files = files;
        this.transaction = transaction;
        this.encryption = encryption;
        this.clock = clock;
    }

    public FileRecord upload(Upload upload) {
        CategoryPolicy policy = policies.policy(upload.category());
        if (upload.size() > policy.maxSizeBytes()) {
            throw IngestErrors.tooLarge(upload.category(), policy.maxSizeBytes());
        }
        ContentWriter.Stored stored = writer.write(upload.category(), upload.size(), upload.contentType(),
                upload.source(), policy.encrypted() ? encryption : null);
        // Numele original al documentelor se păstrează doar criptat (nu apare nici în loguri).
        String storedName = policy.encrypted() ? encryption.encryptName(upload.originalName()) : upload.originalName();
        FileRecord record = new FileRecord(UUID.randomUUID(), upload.category(), storedName,
                upload.contentType(), upload.size(), stored.sha256(), stored.sha256Plain(), stored.storage(),
                stored.key(), FileRecord.CONFIRMED, policy.encrypted(), now(), upload.createdBy(), upload.outage());
        try {
            transaction.executeWithoutResult(status -> files.insert(record));
        } catch (RuntimeException e) {
            log.error("Metadatele fișierului {} nu s-au salvat; șterg obiectul din {}: {}", record.id(),
                    stored.storage(), e.getMessage());
            writer.deleteQuietly(upload.category(), stored.storage(), stored.key());
            throw IngestErrors.storageUnavailable("Baza de metadate este indisponibilă; fișierul nu a fost salvat.");
        }
        return policy.encrypted() ? record.withOriginalName(upload.originalName()) : record;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** O cerere de upload validată de controller. */
    public record Upload(Category category, String originalName, String contentType, long size,
                         ContentWriter.Source source, FileRecord.OutageInfo outage, String createdBy) {
    }
}
