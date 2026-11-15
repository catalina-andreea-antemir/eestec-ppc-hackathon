package ro.filegrid.api;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * An error that becomes an {@link ApiError} response; thrown by controllers and services and turned into JSON
 * by {@link ApiExceptionHandler} (with {@code Retry-After} when relevant).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Integer retryAfterSeconds;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null);
    }

    public ApiException(HttpStatus status, String code, String message, Integer retryAfterSeconds,
                        Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
        this.details = details;
    }

    private final Map<String, String> headers = new java.util.LinkedHashMap<>();

    /** Extra header on the error response (e.g. {@code Content-Range} on 416). */
    public ApiException withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Map<String, Object> details() {
        return details;
    }
}
