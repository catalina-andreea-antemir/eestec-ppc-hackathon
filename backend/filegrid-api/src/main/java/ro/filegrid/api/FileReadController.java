package ro.filegrid.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ro.filegrid.domain.Category;
import ro.filegrid.download.FileQueryService;
import ro.filegrid.ingest.IngestErrors;

/** {@code GET /api/v1/files} (căutare cu paginare) și {@code GET /api/v1/files/{id}} (C8). */
@RestController
public class FileReadController {

    static final int MAX_QUERY_LENGTH = 200;

    private final ObjectProvider<FileQueryService> service;

    public FileReadController(ObjectProvider<FileQueryService> service) {
        this.service = service;
    }

    /** Schema {@code FilePage}. */
    public record FilePage(List<FileMetadata> items, int page, int size, long totalItems, int totalPages) {
    }

    @GetMapping("/api/v1/files")
    public FilePage search(HttpServletRequest request, Authentication authentication,
                           @RequestParam(required = false) String category, @RequestParam(required = false) String q,
                           @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                           @RequestParam(required = false) String page, @RequestParam(required = false) String size) {
        RequestScenario.read(request);
        Category parsedCategory = category(category);
        String text = q == null || q.isBlank() ? null : q.strip();
        if (text != null && text.length() > MAX_QUERY_LENGTH) {
            throw IngestErrors.validation("q", "Parametrul „q” are cel mult 200 de caractere.");
        }
        int parsedPage = integer("page", page, 1, 1, Integer.MAX_VALUE);
        int parsedSize = integer("size", size, 20, 1, FileQueryService.MAX_PAGE_SIZE);
        FileQueryService.Page result = require().search(authentication, parsedCategory, text, instant("from", from),
                instant("to", to), parsedPage, parsedSize);
        return new FilePage(result.items().stream().map(FileMetadata::of).toList(), result.page(), result.size(),
                result.totalItems(), result.totalPages());
    }

    @GetMapping("/api/v1/files/{id}")
    public FileMetadata get(HttpServletRequest request, Authentication authentication, @PathVariable String id) {
        RequestScenario.read(request);
        return FileMetadata.of(require().get(authentication, fileId(id)));
    }

    /** Un id care nu e UUID nu poate exista: 404, ca în contract (fără 400 la aceste rute). */
    static UUID fileId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Fișierul nu există.");
        }
    }

    private FileQueryService require() {
        FileQueryService query = service.getIfAvailable();
        if (query == null) {
            throw IngestErrors.storageUnavailable("Baza de metadate nu este configurată (profilul db).");
        }
        return query;
    }

    private static Category category(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Category.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw IngestErrors.validation("category", "Parametrul „category” nu este o categorie cunoscută.");
        }
    }

    static int integer(String field, String value, int fallback, int min, int max) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // cade în eroarea de mai jos
        }
        throw IngestErrors.validation(field, "Parametrul „" + field + "” trebuie să fie un întreg între " + min
                + " și " + (max == Integer.MAX_VALUE ? "valoarea maximă" : max) + ".");
    }

    private static Instant instant(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException e) {
            throw IngestErrors.validation(field, "Parametrul „" + field
                    + "” trebuie să fie o dată ISO-8601 cu fus orar (de exemplu 2026-11-14T09:32:00Z).");
        }
    }
}
