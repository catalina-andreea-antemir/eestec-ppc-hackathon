package ro.filegrid.ingest;

import java.util.Map;

import org.springframework.http.HttpStatus;

import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;

/** Ingest errors in the contract's {@code Error} format (stable codes). */
public final class IngestErrors {

    public static final int STORAGE_RETRY_AFTER_SECONDS = 10;
    public static final int BATCH_RETRY_AFTER_SECONDS = 2;

    private IngestErrors() {
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, null,
                field == null ? null : Map.of("field", field));
    }

    public static ApiException tooLarge(Category category, long maxBytes) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "Fișierul depășește mărimea maximă permisă pentru " + category + " (" + maxBytes + " octeți).", null,
                Map.of("maxBytes", maxBytes));
    }

    public static ApiException storageUnavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", message,
                STORAGE_RETRY_AFTER_SECONDS, null);
    }
}
