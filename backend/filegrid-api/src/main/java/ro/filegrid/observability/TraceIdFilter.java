package ro.filegrid.observability;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Adds the {@code X-Trace-Id} header to every response (including 401 / 403 from the security filters), using the
 * request's Micrometer Tracing traceId, the same one found in the JSON logs (Loki). Runs after Spring Boot's
 * observation filter (which creates the span) and before Spring Security. If tracing is off (e.g. in tests), it
 * generates an id and puts it in the MDC as {@code traceId}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    /** Request attribute holding the traceId, read by {@link #traceId(HttpServletRequest)}. */
    public static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectProvider<Tracer> tracer;

    public TraceIdFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /** The request's traceId (for the {@code traceId} field of error bodies). */
    public static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value == null ? null : value.toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = currentTraceId();
        boolean generated = traceId == null;
        if (generated) {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            traceId = HexFormat.of().formatHex(bytes);
            MDC.put("traceId", traceId);
        }
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (generated) {
                MDC.remove("traceId");
            }
        }
    }

    private String currentTraceId() {
        Tracer t = tracer.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        if (span == null) {
            return null;
        }
        String id = span.context().traceId();
        // The noop tracer (tracing disabled) returns an empty or all-zero id.
        return id == null || id.isEmpty() || id.chars().allMatch(c -> c == '0') ? null : id;
    }
}
