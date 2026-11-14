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
 * Pune header-ul {@code X-Trace-Id} pe fiecare răspuns (inclusiv 401 / 403 din filtrele de securitate), cu traceId-ul
 * Micrometer Tracing al cererii, același din logurile JSON (Loki). Rulează după filtrul de observare al Spring Boot
 * (care creează span-ul) și înaintea Spring Security. Dacă tracing-ul nu e activ (de exemplu în teste), generează un
 * identificator și îl pune în MDC ca {@code traceId}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    /** Atributul cererii cu traceId-ul, citit de {@link #traceId(HttpServletRequest)}. */
    public static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectProvider<Tracer> tracer;

    public TraceIdFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /** TraceId-ul cererii (pentru câmpul {@code traceId} din corpul erorilor). */
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
        // Tracer-ul noop (tracing dezactivat) întoarce un id gol sau numai zerouri.
        return id == null || id.isEmpty() || id.chars().allMatch(c -> c == '0') ? null : id;
    }
}
