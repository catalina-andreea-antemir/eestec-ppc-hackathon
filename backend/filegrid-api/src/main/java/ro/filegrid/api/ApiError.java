package ro.filegrid.api;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Formatul unic al erorilor (contract: schema {@code Error}). {@code code} este stabil (de exemplu
 * {@code UNAUTHORIZED}, {@code VALIDATION_ERROR}); {@code message} este în română, fără date personale.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String code, String message, String traceId, Integer retryAfterSeconds,
                       Map<String, Object> details) {

    public static ApiError of(int status, String code, String message, String traceId) {
        return new ApiError(status, code, message, traceId, null, null);
    }
}
