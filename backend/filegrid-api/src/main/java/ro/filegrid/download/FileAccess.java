package ro.filegrid.download;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;

import ro.filegrid.domain.Category;
import ro.filegrid.ingest.FileRecord;

/**
 * File access (C8 -> C10): per-category role check (matrix 4.4), audit of connection document access and their
 * decryption. Implemented by {@link RoleBasedFileAccess}.
 */
public interface FileAccess {

    /** The "upload" action (matrix column "Can upload"); every other action is a read ("Can search and download"). */
    String UPLOAD = "UPLOAD";

    /**
     * The single place where role vs. category is checked (matrix 4.4); 403 {@code FORBIDDEN} if not allowed.
     * A denial on {@code CONNECTION_DOC} is audited and increments {@code filegrid_doc_access_total{result="denied"}}.
     *
     * @param action the action ({@link #UPLOAD}, {@code SEARCH}, {@code DOWNLOAD}, {@code VERIFY}, ...)
     * @param fileId the target file, if it already exists (otherwise {@code null})
     */
    void requireRole(Authentication user, Category category, String action, UUID fileId);

    /** Read check without a specific file. */
    default void requireRole(Authentication user, Category category) {
        requireRole(user, category, "READ", null);
    }

    /** Categories the role may search (contract {@code GET /files}). */
    Set<Category> visibleCategories(Authentication user);

    /**
     * Audit of a successful access ({@code CONNECTION_DOC} only); {@code user} is {@code null} for signed links. If the
     * audit row cannot be written, access is denied (503).
     */
    default void audit(Authentication user, FileRecord file, String action) {
        auditAs(user == null ? "download-link" : user.getName(), file, action);
    }

    /** Same, with the actor given explicitly (e.g. the user who requested the signed link). */
    void auditAs(String actor, FileRecord file, String action);

    /** An operation on a document failed after access was granted ({@code result="error"}). */
    default void failed(Category category, String action) {
    }

    /** Decryption step: stored bytes -> plaintext, streamed (only for {@code encrypted} files). */
    InputStream decrypt(FileRecord file, InputStream stored);

    /** Plaintext original name (encrypted in the database for documents); call only after the role check. */
    FileRecord reveal(FileRecord file);
}
