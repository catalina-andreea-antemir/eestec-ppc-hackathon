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
 * {@code /verify}, {@code /download}, {@code /download-link} and {@code GET /downloads/{token}} (C8). The body is
 * streamed straight to the response; the signed link behaves like {@code /download} but needs no {@code Authorization}.
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
        FileRecord file = queryService().get(authentication, FileReadController.fileId(id), "VERIFY");
        try (var permit = bulkheads.acquire(file.category())) {
            access.audit(authentication, file, "VERIFY");
            return contentService().verify(file, Boolean.parseBoolean(deep));
        } catch (RuntimeException e) {
            failed(file, "VERIFY", e);
            throw e;
        }
    }

    @GetMapping("/api/v1/files/{id}/download")
    public void download(HttpServletRequest request, HttpServletResponse response, Authentication authentication,
                         @PathVariable String id, @RequestHeader(value = HttpHeaders.RANGE, required = false) String range)
            throws IOException {
        RequestScenario.read(request);
        FileRecord file = queryService().get(authentication, FileReadController.fileId(id), "DOWNLOAD");
        // The permit is released only when send() returns: stream finished, or closed by a dropped client.
        try (var permit = bulkheads.acquire(file.category())) {
            access.audit(authentication, file, "DOWNLOAD");
            contentService().send(file, range, response);
        } catch (RuntimeException e) {
            failed(file, "DOWNLOAD", e);
            throw e;
        }
    }

    @PostMapping("/api/v1/files/{id}/download-link")
    public DownloadLink link(HttpServletRequest request, Authentication authentication, @PathVariable String id) {
        RequestScenario.read(request);
        FileRecord file = queryService().get(authentication, FileReadController.fileId(id), "DOWNLOAD_LINK");
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
            access.auditAs("link:" + claims.userTag(), file, "DOWNLOAD");
            contentService().send(file, range, response);
        } catch (RuntimeException e) {
            failed(file, "DOWNLOAD", e);
            throw e;
        }
    }

    /** Genuine failure (5xx, interrupted transfer, corrupt file) after access was granted: {@code error} metric. */
    private void failed(FileRecord file, String action, RuntimeException e) {
        boolean clientError = e instanceof ApiException api && api.status().value() < 500 && !"FILE_CORRUPT".equals(api.code());
        if (!clientError) {
            access.failed(file.category(), action);
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
