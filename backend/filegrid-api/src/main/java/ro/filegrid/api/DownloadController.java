package ro.filegrid.api;

import java.io.IOException;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ro.filegrid.download.DownloadTokenService;
import ro.filegrid.download.FileAccess;
import ro.filegrid.download.FileContentService;
import ro.filegrid.download.FileQueryService;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.ingest.IngestErrors;
import ro.filegrid.resilience.CategoryBulkheads;

/**
 * {@code /verify}, {@code /download}, {@code /download-link} și {@code GET /downloads/{token}} (C8). Corpul se scrie
 * direct pe răspuns, în streaming; link-ul semnat se comportă ca {@code /download}, dar fără {@code Authorization}.
 */
@RestController
public class DownloadController {

    private final ObjectProvider<FileQueryService> query;
    private final ObjectProvider<FileContentService> content;
    private final DownloadTokenService tokens;
    private final FileAccess access;
    private final CategoryBulkheads bulkheads;

    public DownloadController(ObjectProvider<FileQueryService> query, ObjectProvider<FileContentService> content,
                              DownloadTokenService tokens, FileAccess access, CategoryBulkheads bulkheads) {
        this.bulkheads = bulkheads;
        this.query = query;
        this.content = content;
        this.tokens = tokens;
        this.access = access;
    }

    /** Schema {@code DownloadLink}. */
    public record DownloadLink(String url, Instant expiresAt) {
    }

    @GetMapping("/api/v1/files/{id}/verify")
    public FileContentService.VerifyResult verify(HttpServletRequest request, Authentication authentication,
                                                  @PathVariable String id,
                                                  @RequestParam(defaultValue = "false") String deep) {
        RequestScenario.read(request);
        FileRecord file = queryService().get(authentication, FileReadController.fileId(id));
        try (var permit = bulkheads.acquire(file.category())) {
            access.audit(authentication, file, "VERIFY");
            return contentService().verify(file, Boolean.parseBoolean(deep));
        }
    }

    @GetMapping("/api/v1/files/{id}/download")
    public void download(HttpServletRequest request, HttpServletResponse response, Authentication authentication,
                         @PathVariable String id, @RequestHeader(value = HttpHeaders.RANGE, required = false) String range)
            throws IOException {
        RequestScenario.read(request);
        FileRecord file = queryService().get(authentication, FileReadController.fileId(id));
        // Permisul se eliberează abia când send() se întoarce: stream terminat, sau închis de un client căzut.
        try (var permit = bulkheads.acquire(file.category())) {
            access.audit(authentication, file, "DOWNLOAD");
            contentService().send(file, range, response);
        }
    }

    @PostMapping("/api/v1/files/{id}/download-link")
    public DownloadLink link(HttpServletRequest request, Authentication authentication, @PathVariable String id) {
        RequestScenario.read(request);
        FileRecord file = queryService().get(authentication, FileReadController.fileId(id));
        access.audit(authentication, file, "DOWNLOAD_LINK");
        DownloadTokenService.Link link = tokens.create(file.id(), authentication.getName());
        return new DownloadLink("/api/v1/downloads/" + link.token(), link.expiresAt());
    }

    @GetMapping("/api/v1/downloads/{token}")
    public void downloadByToken(HttpServletRequest request, HttpServletResponse response, @PathVariable String token,
                                @RequestHeader(value = HttpHeaders.RANGE, required = false) String range)
            throws IOException {
        RequestScenario.read(request);
        DownloadTokenService.Claims claims = tokens.verify(token);
        FileRecord file = queryService().lookup(claims.fileId());
        try (var permit = bulkheads.acquire(file.category())) {
            contentService().send(file, range, response);
        }
    }

    private FileQueryService queryService() {
        FileQueryService service = query.getIfAvailable();
        if (service == null) {
            throw IngestErrors.storageUnavailable("Baza de metadate nu este configurată (profilul db).");
        }
        return service;
    }

    private FileContentService contentService() {
        FileContentService service = content.getIfAvailable();
        if (service == null) {
            throw IngestErrors.storageUnavailable("Baza de metadate nu este configurată (profilul db).");
        }
        return service;
    }
}
