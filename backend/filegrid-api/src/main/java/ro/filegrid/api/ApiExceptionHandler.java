package ro.filegrid.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import ro.filegrid.observability.TraceIdFilter;

/** Transformă excepțiile în formatul {@link ApiError} din contract. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
        if (e.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds().toString());
        }
        if (e.status() == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(new ApiError(e.status().value(), e.code(), e.getMessage(),
                TraceIdFilter.traceId(request), e.retryAfterSeconds(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        var fieldError = e.getBindingResult().getFieldError();
        String field = fieldError == null ? null : fieldError.getField();
        String message = field == null ? "Cerere invalidă." : "Câmpul „" + field + "” este invalid sau lipsește.";
        return ResponseEntity.badRequest().body(new ApiError(400, "VALIDATION_ERROR", message,
                TraceIdFilter.traceId(request), null, field == null ? null : Map.of("field", field)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiError.of(400, "VALIDATION_ERROR",
                "Corpul cererii lipsește sau nu este JSON valid.", TraceIdFilter.traceId(request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(NoResourceFoundException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "NOT_FOUND",
                "Resursa nu există.", TraceIdFilter.traceId(request)));
    }
}
