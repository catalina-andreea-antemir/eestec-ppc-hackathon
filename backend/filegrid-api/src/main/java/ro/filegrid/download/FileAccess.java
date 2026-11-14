package ro.filegrid.download;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;

import ro.filegrid.domain.Category;
import ro.filegrid.ingest.FileRecord;

/**
 * Accesul la fișiere (C8 → C10): rolul pe categorie (matricea 4.4), auditul accesului la documentele de racordare și
 * decriptarea lor. Implementarea e {@link RoleBasedFileAccess}.
 */
public interface FileAccess {

    /** Acțiunea „încărcare” (matricea „Poate încărca”); orice altă acțiune e citire („Poate căuta și descărca”). */
    String UPLOAD = "UPLOAD";

    /**
     * Singurul loc unde se verifică rolul față de categorie (matricea 4.4); 403 {@code FORBIDDEN} dacă nu e permis.
     * Un refuz la {@code CONNECTION_DOC} se auditează și incrementează {@code filegrid_doc_access_total{result="denied"}}.
     *
     * @param action acțiunea ({@link #UPLOAD}, {@code SEARCH}, {@code DOWNLOAD}, {@code VERIFY}, ...)
     * @param fileId fișierul vizat, dacă există deja (altfel {@code null})
     */
    void requireRole(Authentication user, Category category, String action, UUID fileId);

    /** Verificare de citire fără fișier anume. */
    default void requireRole(Authentication user, Category category) {
        requireRole(user, category, "READ", null);
    }

    /** Categoriile pe care le poate căuta rolul (contract {@code GET /files}). */
    Set<Category> visibleCategories(Authentication user);

    /**
     * Auditul accesului reușit (doar {@code CONNECTION_DOC}); {@code user} e {@code null} la link semnat. Dacă auditul
     * nu se poate scrie, accesul se refuză (503).
     */
    default void audit(Authentication user, FileRecord file, String action) {
        auditAs(user == null ? "download-link" : user.getName(), file, action);
    }

    /** La fel, cu actorul dat direct (de exemplu utilizatorul care a cerut linkul semnat). */
    void auditAs(String actor, FileRecord file, String action);

    /** O operație pe un document a eșuat după ce accesul a fost permis ({@code result="error"}). */
    default void failed(Category category, String action) {
    }

    /** Pasul de decriptare: octeții stocați → conținutul în clar, în streaming (doar pentru {@code encrypted}). */
    InputStream decrypt(FileRecord file, InputStream stored);

    /** Numele original în clar (la documente e criptat în baza de date); apelat doar după verificarea rolului. */
    FileRecord reveal(FileRecord file);
}
