package ro.filegrid.download;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.ingest.DocumentEncryption;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.ingest.IngestErrors;
import ro.filegrid.security.AuditLog;
import ro.filegrid.security.Role;

/**
 * Role matrix 4.4, connection document audit and decryption (C10). The audit and the
 * {@code filegrid_doc_access_total{result="ok|denied|error"}} metric only apply to {@code CONNECTION_DOC}.
 */
@Component
public class RoleBasedFileAccess implements FileAccess {

    private static final Logger log = LoggerFactory.getLogger(RoleBasedFileAccess.class);
    private static final Set<Category> ALL = Set.of(Category.values());

    /** "Can upload". */
    static final Map<Role, Set<Category>> UPLOAD = new EnumMap<>(Role.class);
    /** "Can search and download". */
    static final Map<Role, Set<Category>> READ = new EnumMap<>(Role.class);

    static {
        UPLOAD.put(Role.METER_SERVICE, Set.of(Category.METER_READING));
        UPLOAD.put(Role.FIELD, Set.of(Category.OUTAGE_RECORD, Category.INSPECTION_VIDEO));
        UPLOAD.put(Role.DISPATCH, Set.of());
        UPLOAD.put(Role.CONNECTIONS, Set.of(Category.CONNECTION_DOC));
        UPLOAD.put(Role.ADMIN, ALL);
        READ.put(Role.METER_SERVICE, Set.of());
        READ.put(Role.FIELD, Set.of(Category.OUTAGE_RECORD, Category.INSPECTION_VIDEO));
        READ.put(Role.DISPATCH, Set.of(Category.OUTAGE_RECORD, Category.METER_READING));
        READ.put(Role.CONNECTIONS, Set.of(Category.CONNECTION_DOC));
        READ.put(Role.ADMIN, ALL);
    }

    private final ObjectProvider<AuditLog> auditLog;
    private final DocumentEncryption encryption;
    private final Counter ok;
    private final Counter denied;
    private final Counter error;

    public RoleBasedFileAccess(ObjectProvider<AuditLog> auditLog, DocumentEncryption encryption, MeterRegistry registry) {
        this.auditLog = auditLog;
        this.encryption = encryption;
        this.ok = counter(registry, "ok");
        this.denied = counter(registry, "denied");
        this.error = counter(registry, "error");
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder("filegrid.doc.access").description("Accesuri la documentele de racordare")
                .tag("result", result).register(registry);
    }

    @Override
    public void requireRole(Authentication user, Category category, String action, UUID fileId) {
        Role role = roleOf(user);
        Map<Role, Set<Category>> matrix = UPLOAD_ACTION.equals(action) ? UPLOAD : READ;
        if (role != null && matrix.get(role).contains(category)) {
            return;
        }
        if (category == Category.CONNECTION_DOC) {
            denied.increment();
            try {
                append(user == null ? "anonymous" : user.getName(), action + "_DENIED", fileId);
            } catch (RuntimeException e) {
                log.error("Document access denial could not be written to the audit log: {}", e.getMessage());
            }
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Rolul tău nu are acces la această categorie.");
    }

    private static final String UPLOAD_ACTION = FileAccess.UPLOAD;

    @Override
    public Set<Category> visibleCategories(Authentication user) {
        Role role = roleOf(user);
        return role == null ? Set.of() : READ.get(role);
    }

    @Override
    public void auditAs(String actor, FileRecord file, String action) {
        if (file.category() != Category.CONNECTION_DOC) {
            return;
        }
        try {
            append(actor, action, file.id());
        } catch (RuntimeException e) {
            error.increment();
            log.error("Audit {} for file {} could not be written; denying access: {}", action, file.id(),
                    e.getMessage());
            throw IngestErrors.storageUnavailable("Jurnalul de audit este indisponibil; accesul la documente se refuză.");
        }
        ok.increment();
    }

    @Override
    public void failed(Category category, String action) {
        if (category == Category.CONNECTION_DOC) {
            error.increment();
        }
    }

    @Override
    public InputStream decrypt(FileRecord file, InputStream stored) {
        return encryption.decrypt(stored);
    }

    @Override
    public FileRecord reveal(FileRecord file) {
        if (!file.encrypted()) {
            return file;
        }
        try {
            return file.withOriginalName(encryption.decryptName(file.originalName()));
        } catch (java.io.IOException e) {
            log.error("Name of file {} cannot be decrypted: {}", file.id(), e.getMessage());
            return file.withOriginalName("fisier");
        }
    }

    private void append(String actor, String action, UUID fileId) {
        AuditLog chain = auditLog.getIfAvailable();
        if (chain == null) {
            throw new IllegalStateException("Jurnalul de audit nu este configurat (profilul db)");
        }
        chain.append(actor, action, fileId);
    }

    private static Role roleOf(Authentication user) {
        if (user == null) {
            return null;
        }
        for (GrantedAuthority authority : user.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                try {
                    return Role.valueOf(name.substring(5));
                } catch (IllegalArgumentException e) {
                    // unknown authority: ignored
                }
            }
        }
        return null;
    }
}
