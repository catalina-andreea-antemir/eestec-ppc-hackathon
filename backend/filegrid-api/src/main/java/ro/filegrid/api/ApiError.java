package ro.filegrid.api;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Single error format (contract schema {@code Error}). {@code code} is stable (e.g. {@code UNAUTHORIZED},
 * {@code VALIDATION_ERROR}); {@code message} is in Romanian (shown as-is by the UI) and never contains personal data.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String code, String message, String traceId, Integer retryAfterSeconds,
                       Map<String, Object> details) {

    public static ApiError of(int status, String code, String message, String traceId) {
        return new ApiError(status, code, message, traceId, null, null);
    }
}
