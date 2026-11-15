package ro.filegrid.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

import ro.filegrid.domain.Category;
import ro.filegrid.download.FileAccess;
import ro.filegrid.ingest.FileIngestionService;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.ingest.IngestErrors;
import ro.filegrid.ingest.Zones;
import ro.filegrid.resilience.CategoryBulkheads;

/**
 * {@code POST /api/v1/files} (C7): multipart upload for {@code OUTAGE_RECORD} and {@code CONNECTION_DOC}.
 * The file lands in the container's multipart temp file (deleted by Spring after the request) and is streamed
 * from there to storage.
 */
@RestController
public class FileController {

    static final int MAX_NAME_LENGTH = 255;
    static final int MAX_CONTENT_TYPE_LENGTH = 255;
    static final int MAX_STATION_ID_LENGTH = 128;

    private final ObjectProvider<FileIngestionService> service;

    private final CategoryBulkheads bulkheads;
    private final FileAccess access;

    public FileController(ObjectProvider<FileIngestionService> service, CategoryBulkheads bulkheads, FileAccess access) {
        this.service = service;
        this.bulkheads = bulkheads;
        this.access = access;
    }

    @PostMapping("/api/v1/files")
    public ResponseEntity<FileMetadata> upload(HttpServletRequest request, Authentication authentication) {
        RequestScenario.read(request);
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        if (multipart == null) {
            throw IngestErrors.validation(null, "Cererea trebuie să fie multipart/form-data.");
        }
        Category category = category(multipart.getParameter("category"));
        access.requireRole(authentication, category, FileAccess.UPLOAD, null);
        MultipartFile file = multipart.getFile("file");
        if (file == null || file.isEmpty()) {
            throw IngestErrors.validation("file", "Câmpul „file” lipsește sau fișierul este gol.");
        }
        FileRecord.OutageInfo outage = category == Category.OUTAGE_RECORD ? outage(multipart) : null;
        FileIngestionService ingestion = service.getIfAvailable();
        if (ingestion == null) {
            throw IngestErrors.storageUnavailable("Baza de metadate nu este configurată (profilul db).");
        }
        // The category comes from the multipart body, already parsed by the container; take the permit before the heavy (storage) work.
        try (var permit = bulkheads.acquire(category)) {
            FileRecord stored;
            try {
                stored = ingestion.upload(new FileIngestionService.Upload(category,
                    originalName(file.getOriginalFilename()), contentType(file.getContentType()), file.getSize(),
                    file::getInputStream, outage, authentication.getName()));
            } catch (RuntimeException e) {
                access.failed(category, FileAccess.UPLOAD);
                throw e;
            }
            access.audit(authentication, stored, FileAccess.UPLOAD);
            return ResponseEntity.status(HttpStatus.CREATED).body(FileMetadata.of(stored));
        }
    }

    private static Category category(String value) {
        if (Category.OUTAGE_RECORD.name().equals(value) || Category.CONNECTION_DOC.name().equals(value)) {
            return Category.valueOf(value);
        }
        throw IngestErrors.validation("category", value == null
                ? "Câmpul „category” este obligatoriu."
                : "Câmpul „category” acceptă doar OUTAGE_RECORD sau CONNECTION_DOC.");
    }

    private static FileRecord.OutageInfo outage(MultipartHttpServletRequest request) {
        String zone = request.getParameter("zone");
        if (zone == null || zone.isBlank()) {
            throw IngestErrors.validation("zone", "Câmpul „zone” este obligatoriu pentru OUTAGE_RECORD.");
        }
        if (!Zones.VALID.contains(zone)) {
            throw IngestErrors.validation("zone", "Câmpul „zone” acceptă doar Muntenia, Banat sau Dobrogea.");
        }
        String stationId = request.getParameter("stationId");
        if (stationId != null) {
            stationId = stationId.strip();
            if (stationId.isEmpty()) {
                stationId = null;
            } else if (stationId.length() > MAX_STATION_ID_LENGTH) {
                throw IngestErrors.validation("stationId", "Câmpul „stationId” are cel mult 128 de caractere.");
            }
        }
        return new FileRecord.OutageInfo(zone, stationId, occurredAt(request.getParameter("occurredAt")));
    }

    private static Instant occurredAt(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException e) {
            throw IngestErrors.validation("occurredAt",
                    "Câmpul „occurredAt” trebuie să fie o dată ISO-8601 cu fus orar (de exemplu 2026-11-14T09:32:00Z).");
        }
    }

    /** Bare name only (no path), no control characters, at most 255 characters. */
    static String originalName(String name) {
        String clean = name == null ? "" : name.replaceAll("\\p{Cntrl}", "");
        clean = clean.substring(Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\')) + 1).strip();
        if (clean.isEmpty()) {
            return "fisier";
        }
        return clean.length() > MAX_NAME_LENGTH ? clean.substring(0, MAX_NAME_LENGTH) : clean;
    }

    static String contentType(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_CONTENT_TYPE_LENGTH) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        return value;
    }
}
