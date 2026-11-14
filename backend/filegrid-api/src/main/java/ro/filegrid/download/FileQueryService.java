package ro.filegrid.download;

import java.time.Instant;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.ingest.FileMetadataRepository;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.ingest.FileSearch;
import ro.filegrid.ingest.IngestErrors;

/** {@code GET /files} și {@code GET /files/{id}} (C8): citește din {@code files}, rolul vine din {@link FileAccess}. */
@Service
@Profile("db")
public class FileQueryService {

    public static final int MAX_PAGE_SIZE = 100;

    private final FileMetadataRepository files;
    private final FileAccess access;

    public FileQueryService(FileMetadataRepository files, FileAccess access) {
        this.files = files;
        this.access = access;
    }

    /** Pagina de rezultate; fără {@code category} se restrânge la categoriile permise rolului. */
    public Page search(Authentication user, Category category, String q, Instant from, Instant to, int page,
                       int size) {
        if (category != null) {
            access.requireRole(user, category);
        }
        FileSearch.Result result;
        try {
            result = files.search(new FileSearch(access.visibleCategories(user), category, q, from, to, page, size));
        } catch (DataAccessException e) {
            throw metadataUnavailable();
        }
        long pages = (result.total() + size - 1) / size;
        return new Page(result.items(), page, size, result.total(), (int) pages);
    }

    /** Fișierul confirmat, după verificarea rolului pe categorie. */
    public FileRecord get(Authentication user, UUID id) {
        FileRecord file = lookup(id);
        access.requireRole(user, file.category());
        return file;
    }

    /** Fișierul confirmat, fără verificarea rolului (link semnat: rolul s-a verificat la crearea lui). */
    public FileRecord lookup(UUID id) {
        try {
            return files.findWithOutage(id).filter(f -> FileRecord.CONFIRMED.equals(f.status()))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Fișierul nu există."));
        } catch (DataAccessException e) {
            throw metadataUnavailable();
        }
    }

    static ApiException metadataUnavailable() {
        return IngestErrors.storageUnavailable("Baza de metadate este indisponibilă.");
    }

    /** Schema {@code FilePage}. */
    public record Page(java.util.List<FileRecord> items, int page, int size, long totalItems, int totalPages) {
    }
}
