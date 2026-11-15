package ro.filegrid.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ro.filegrid.domain.Category;
import ro.filegrid.download.FileAccess;
import ro.filegrid.ingest.IngestErrors;
import ro.filegrid.resilience.CategoryBulkheads;
import ro.filegrid.upload.ChunkAck;
import ro.filegrid.upload.UploadService;
import ro.filegrid.upload.UploadStatus;

/**
 * Chunked upload (C7, ADR-003): {@code POST /uploads}, {@code PUT /uploads/{id}/chunks/{n}} (the binary body is
 * streamed straight from the request), {@code GET /uploads/{id}}, {@code POST /uploads/{id}/complete} and
 * {@code POST /uploads/{id}/abort}. A session can only be used by whoever started it, or by ADMIN.
 */
@RestController
public class UploadController {

    private final ObjectProvider<UploadService> service;
    private final CategoryBulkheads bulkheads;
    private final FileAccess access;

    public UploadController(ObjectProvider<UploadService> service, CategoryBulkheads bulkheads, FileAccess access) {
        this.access = access;
        this.bulkheads = bulkheads;
        this.service = service;
    }

    /** Body of {@code UploadInit}. */
    public record UploadInit(String category, String fileName, Long totalSize) {
    }

    @PostMapping("/api/v1/uploads")
    public ResponseEntity<UploadStatus> init(HttpServletRequest request, Authentication authentication,
                                             @RequestBody UploadInit body) {
        RequestScenario.read(request);
        if (!Category.INSPECTION_VIDEO.name().equals(body.category())) {
            throw IngestErrors.validation("category", "Câmpul „category” acceptă doar INSPECTION_VIDEO.");
        }
        if (body.totalSize() == null || body.totalSize() < 1) {
            throw IngestErrors.validation("totalSize", "Câmpul „totalSize” este obligatoriu și pozitiv.");
        }
        access.requireRole(authentication, Category.INSPECTION_VIDEO, FileAccess.UPLOAD, null);
        try (var permit = bulkheads.acquire(Category.INSPECTION_VIDEO)) {
            UploadStatus created = uploads().init(Category.INSPECTION_VIDEO,
                    FileController.originalName(body.fileName()), body.totalSize(), caller(authentication));
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        }
    }

    @GetMapping("/api/v1/uploads/{id}")
    public UploadStatus get(@PathVariable String id, Authentication authentication) {
        return uploads().get(uploadId(id), caller(authentication));
    }

    @PutMapping(path = "/api/v1/uploads/{id}/chunks/{n}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ChunkAck putChunk(@PathVariable String id, @PathVariable int n, HttpServletRequest request,
                             Authentication authentication) throws IOException {
        RequestScenario.read(request);
        UploadService.Caller caller = caller(authentication);
        UUID uploadId = uploadId(id);
        try (var permit = bulkheads.acquire(uploads().categoryOf(uploadId, caller))) { // before reading the body
            return uploads().putChunk(uploadId, n, caller, request.getInputStream(), request.getContentLengthLong());
        }
    }

    @PostMapping("/api/v1/uploads/{id}/complete")
    public ResponseEntity<FileMetadata> complete(@PathVariable String id, Authentication authentication) {
        UploadService.Caller caller = caller(authentication);
        UUID uploadId = uploadId(id);
        try (var permit = bulkheads.acquire(uploads().categoryOf(uploadId, caller))) {
            UploadService.Completed done = uploads().complete(uploadId, caller);
            return ResponseEntity.status(done.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(FileMetadata.of(done.file()));
        }
    }

    @PostMapping("/api/v1/uploads/{id}/abort")
    public UploadStatus abort(@PathVariable String id, Authentication authentication) {
        UploadService.Caller caller = caller(authentication);
        UUID uploadId = uploadId(id);
        try (var permit = bulkheads.acquire(uploads().categoryOf(uploadId, caller))) {
            return uploads().abort(uploadId, caller);
        }
    }

    private UploadService uploads() {
        UploadService uploads = service.getIfAvailable();
        if (uploads == null) {
            throw IngestErrors.storageUnavailable("Baza de metadate nu este configurată (profilul db).");
        }
        return uploads;
    }

    private static UUID uploadId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw IngestErrors.validation("id", "uploadId trebuie să fie un UUID.");
        }
    }

    private static UploadService.Caller caller(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return new UploadService.Caller(authentication.getName(), admin);
    }
}
