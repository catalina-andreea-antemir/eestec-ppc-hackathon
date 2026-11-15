package ro.filegrid.api;

import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import ro.filegrid.ingest.IngestErrors;

/**
 * Optional {@code X-Scenario} header ({@code normal}, {@code peak}, {@code storm}...). Validated and kept as a
 * request attribute; the C12 recorder writes it to {@code operation_metrics}.
 */
public final class RequestScenario {

    public static final String HEADER = "X-Scenario";
    public static final String ATTRIBUTE = RequestScenario.class.getName();
    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1,32}");

    private RequestScenario() {
    }

    /** Reads and stores the scenario; an invalid value -> 400. */
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

    /** The scenario stored by {@link #read(HttpServletRequest)}, if any. */
    public static Optional<String> of(HttpServletRequest request) {
        return Optional.ofNullable((String) request.getAttribute(ATTRIBUTE));
    }
}
