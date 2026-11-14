package ro.filegrid.download;

import java.io.InputStream;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.ingest.FileRecord;

/** Implementarea până la C10: orice rol autentificat vede toate categoriile, fără audit, fără decriptare. */
@Component
class DefaultFileAccess implements FileAccess {

    @Override
    public void requireRole(Authentication user, Category category) {
        // C10: matricea de roluri.
    }

    @Override
    public Set<Category> visibleCategories(Authentication user) {
        return Set.of(Category.values());
    }

    @Override
    public void audit(Authentication user, FileRecord file, String action) {
        // C10: filegrid_doc_access_total și jurnalul de audit.
    }

    @Override
    public InputStream decrypt(FileRecord file, InputStream stored) {
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED",
                "Decriptarea documentelor nu este încă implementată (C10).");
    }
}
