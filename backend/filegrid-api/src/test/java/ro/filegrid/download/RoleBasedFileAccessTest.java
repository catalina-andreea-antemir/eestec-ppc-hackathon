package ro.filegrid.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ro.filegrid.TestSecrets;
import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.ingest.AesGcmDocumentEncryption;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.security.AuditLog;
import ro.filegrid.security.Role;

/** Matricea rolurilor 4.4: fiecare rol × fiecare categorie × încărcare / căutare-descărcare; audit și metrică la documente. */
class RoleBasedFileAccessTest {

    private static final Map<Role, Set<Category>> UPLOAD = Map.of(
            Role.METER_SERVICE, Set.of(Category.METER_READING),
            Role.FIELD, Set.of(Category.OUTAGE_RECORD, Category.INSPECTION_VIDEO),
            Role.DISPATCH, Set.of(),
            Role.CONNECTIONS, Set.of(Category.CONNECTION_DOC),
            Role.ADMIN, Set.of(Category.values()));
    private static final Map<Role, Set<Category>> READ = Map.of(
            Role.METER_SERVICE, Set.of(),
            Role.FIELD, Set.of(Category.OUTAGE_RECORD, Category.INSPECTION_VIDEO),
            Role.DISPATCH, Set.of(Category.OUTAGE_RECORD, Category.METER_READING),
            Role.CONNECTIONS, Set.of(Category.CONNECTION_DOC),
            Role.ADMIN, Set.of(Category.values()));

    private final AuditLog chain = mock(AuditLog.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AuditLog> provider = mock(ObjectProvider.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AesGcmDocumentEncryption enc = new AesGcmDocumentEncryption(TestSecrets.DOC_KEY_BYTES);
    private final RoleBasedFileAccess access;

    RoleBasedFileAccessTest() {
        when(provider.getIfAvailable()).thenReturn(chain);
        access = new RoleBasedFileAccess(provider, enc, metrics);
    }

    private static UsernamePasswordAuthenticationToken user(Role role) {
        return new UsernamePasswordAuthenticationToken("u-" + role, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private double count(String result) {
        return metrics.counter("filegrid.doc.access", "result", result).count();
    }

    private static FileRecord doc() {
        return new FileRecord(UUID.randomUUID(), Category.CONNECTION_DOC, "x", "text/plain", 1, new byte[32],
                new byte[32], StorageType.DB, "k", FileRecord.CONFIRMED, true, Instant.now(), "u", null);
    }

    @Test
    void everyRoleEveryCategoryUploadAndRead() {
        for (Role role : Role.values()) {
            for (Category category : Category.values()) {
                boolean canUpload = UPLOAD.get(role).contains(category);
                boolean canRead = READ.get(role).contains(category);
                check(canUpload, () -> access.requireRole(user(role), category, FileAccess.UPLOAD, null),
                        role + " upload " + category);
                for (String action : new String[] {"SEARCH", "DOWNLOAD", "VERIFY", "DOWNLOAD_LINK", "VIEW"}) {
                    check(canRead, () -> access.requireRole(user(role), category, action, null),
                            role + " " + action + " " + category);
                }
            }
            assertThat(access.visibleCategories(user(role))).as(role + " visible").isEqualTo(READ.get(role));
        }
    }

    private static void check(boolean allowed, Runnable call, String what) {
        if (allowed) {
            call.run();
        } else {
            assertThatThrownBy(call::run).as(what).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status().value()).isEqualTo(403);
                assertThat(e.code()).isEqualTo("FORBIDDEN");
            });
        }
    }

    @Test
    void unknownOrMissingRoleSeesNothing() {
        var nobody = new UsernamePasswordAuthenticationToken("x", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ALTUL")));
        assertThat(access.visibleCategories(nobody)).isEmpty();
        assertThatThrownBy(() -> access.requireRole(nobody, Category.OUTAGE_RECORD)).isInstanceOf(ApiException.class);
        assertThat(access.visibleCategories(null)).isEmpty();
    }

    @Test
    void deniedDocumentAccessIsCountedAndAudited() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> access.requireRole(user(Role.DISPATCH), Category.CONNECTION_DOC, "DOWNLOAD", id))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> access.requireRole(user(Role.FIELD), Category.CONNECTION_DOC, FileAccess.UPLOAD, null))
                .isInstanceOf(ApiException.class);

        assertThat(count("denied")).isEqualTo(2);
        verify(chain).append("u-DISPATCH", "DOWNLOAD_DENIED", id);
        verify(chain).append("u-FIELD", "UPLOAD_DENIED", null);
    }

    @Test
    void deniedAccessToOtherCategoriesIsNeitherCountedNorAudited() {
        assertThatThrownBy(() -> access.requireRole(user(Role.DISPATCH), Category.INSPECTION_VIDEO))
                .isInstanceOf(ApiException.class);

        assertThat(count("denied")).isZero();
        verify(chain, never()).append(any(), any(), any());
    }

    @Test
    void auditedAccessIsCountedOkAndOnlyForDocuments() {
        FileRecord doc = doc();
        access.audit(user(Role.CONNECTIONS), doc, "DOWNLOAD");
        access.auditAs("link:u-CONNECTIONS", doc, "DOWNLOAD");
        access.audit(user(Role.FIELD), doc.withOriginalName("y"), "VERIFY");
        FileRecord video = new FileRecord(UUID.randomUUID(), Category.INSPECTION_VIDEO, "v", "video/mp4", 1,
                new byte[32], null, StorageType.S3, "k", FileRecord.CONFIRMED, false, Instant.now(), "u", null);
        access.audit(user(Role.FIELD), video, "DOWNLOAD");

        assertThat(count("ok")).isEqualTo(3);
        verify(chain).append("u-CONNECTIONS", "DOWNLOAD", doc.id());
        verify(chain).append("link:u-CONNECTIONS", "DOWNLOAD", doc.id());
        verify(chain, never()).append(eq("u-FIELD"), eq("DOWNLOAD"), eq(video.id()));
        access.failed(Category.CONNECTION_DOC, "DOWNLOAD");
        access.failed(Category.OUTAGE_RECORD, "DOWNLOAD");
        assertThat(count("error")).isEqualTo(1);
    }

    @Test
    void auditFailureRefusesTheAccessWith503() {
        doThrow(new IllegalStateException("db jos")).when(chain).append(any(), any(), any());

        assertThatThrownBy(() -> access.audit(user(Role.CONNECTIONS), doc(), "DOWNLOAD"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
        assertThat(count("ok")).isZero();
        assertThat(count("error")).isEqualTo(1);
        // un refuz rămâne 403 chiar dacă auditul nu se poate scrie
        assertThatThrownBy(() -> access.requireRole(user(Role.DISPATCH), Category.CONNECTION_DOC, "DOWNLOAD", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(403));
    }

    @Test
    void revealDecryptsTheNameOnlyForEncryptedFiles() {
        FileRecord stored = doc().withOriginalName(enc.encryptName("cerere.pdf"));

        assertThat(access.reveal(stored).originalName()).isEqualTo("cerere.pdf");
        FileRecord video = new FileRecord(UUID.randomUUID(), Category.INSPECTION_VIDEO, "video.mp4", "video/mp4", 1,
                new byte[32], null, StorageType.S3, "k", FileRecord.CONFIRMED, false, Instant.now(), "u", null);
        assertThat(access.reveal(video).originalName()).isEqualTo("video.mp4");
    }
}
