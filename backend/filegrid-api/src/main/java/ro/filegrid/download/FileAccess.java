package ro.filegrid.download;

import java.io.InputStream;
import java.util.Set;

import org.springframework.security.core.Authentication;

import ro.filegrid.domain.Category;
import ro.filegrid.ingest.FileRecord;

/**
 * PUNCTELE DE EXTENSIE ale accesului la fișiere (C8 → C10). Rolul pe categorie, auditul și decriptarea documentelor
 * se implementează la C10 în locul {@link DefaultFileAccess}; restul codului din C8 doar le apelează.
 */
public interface FileAccess {

    /** Singurul loc unde se verifică rolul față de categorie (matricea 4.4); 403 dacă nu e permis. */
    void requireRole(Authentication user, Category category);

    /** Categoriile pe care le poate căuta rolul (contract {@code GET /files}). */
    Set<Category> visibleCategories(Authentication user);

    /** Auditul accesului (în primul rând {@code CONNECTION_DOC}); {@code user} e {@code null} la link semnat. */
    void audit(Authentication user, FileRecord file, String action);

    /** Pasul de decriptare: octeții stocați → conținutul în clar, în streaming (doar pentru {@code encrypted}). */
    InputStream decrypt(FileRecord file, InputStream stored);
}
