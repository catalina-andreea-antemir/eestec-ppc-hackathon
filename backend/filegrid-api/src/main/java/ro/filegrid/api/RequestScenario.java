package ro.filegrid.api;

import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import ro.filegrid.ingest.IngestErrors;

/**
 * Header-ul opțional {@code X-Scenario} ({@code normal}, {@code peak}, {@code storm}...). Se validează și se păstrează
 * ca atribut al cererii; îl scrie în {@code operation_metrics} recorder-ul de la C12.
 */
public final class RequestScenario {

    public static final String HEADER = "X-Scenario";
    public static final String ATTRIBUTE = RequestScenario.class.getName();
    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1,32}");

    private RequestScenario() {
    }

    /** Citește și reține scenariul; valoare invalidă → 400. */
    public static Optional<String> read(HttpServletRequest request) {
        String value = request.getHeader(HEADER);
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        if (!VALID.matcher(value).matches()) {
            throw IngestErrors.validation(HEADER, "Header-ul X-Scenario trebuie să respecte ^[a-z0-9_-]{1,32}$.");
        }
        request.setAttribute(ATTRIBUTE, value);
        return Optional.of(value);
    }

    /** Scenariul reținut de {@link #read(HttpServletRequest)}, dacă există. */
    public static Optional<String> of(HttpServletRequest request) {
        return Optional.ofNullable((String) request.getAttribute(ATTRIBUTE));
    }
}
