package ro.filegrid.analytics;

import static ro.filegrid.analytics.StatsResponses.*;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ro.filegrid.api.ApiException;
import ro.filegrid.security.Role;

@RestController
@RequestMapping("/api/v1/stats")
class StatsController {

    private static final Pattern SCENARIO = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Instant MIN_DATETIME2 = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_DATETIME2 = Instant.parse("9999-12-31T23:59:59.999999900Z");

    private final StatsService stats;

    StatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/anomalies")
    Anomalies anomalies(HttpServletRequest request, @RequestParam(required = false) String from,
                        @RequestParam(required = false) String to, @RequestParam(required = false) String silentMinutes,
                        @RequestParam(required = false) String page, @RequestParam(required = false) String size) {
        requireStatsRole(request);
        Instant now = now();
        var window = window(from, to, now, Duration.ofDays(1));
        return stats.anomalies(now, window.from(), window.to(),
                integer("silentMinutes", silentMinutes, 60, Integer.MAX_VALUE),
                integer("page", page, 1, Integer.MAX_VALUE), integer("size", size, 20, 100));
    }

    @GetMapping("/outage-zones")
    OutageZones outageZones(HttpServletRequest request, @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to) {
        requireStatsRole(request);
        Instant now = now();
        var window = window(from, to, now, Duration.ofDays(30));
        return stats.outageZones(now, window.from(), window.to());
    }

    @GetMapping("/peak-vs-normal")
    PeakVsNormal peakVsNormal(HttpServletRequest request, @RequestParam(required = false) String normalScenario,
                      @RequestParam(required = false) String peakScenario) {
        requireStatsRole(request);
        String normal = scenario("normalScenario", normalScenario, "normal");
        String peak = scenario("peakScenario", peakScenario, "storm");
        if (normal.equals(peak)) {
            throw invalid("peakScenario", "Scenariile normal și de vârf trebuie să aibă etichete diferite.");
        }
        return stats.peakVsNormal(now(), normal, peak);
    }

    @GetMapping("/storage-advisor")
    StorageAdvisor storageAdvisor(HttpServletRequest request) {
        requireStatsRole(request);
        return stats.storageAdvisor(now());
    }

    private static void requireStatsRole(HttpServletRequest request) {
        if (!request.isUserInRole(Role.DISPATCH.name()) && !request.isUserInRole(Role.ADMIN.name())) {
            throw new AccessDeniedException("Accesul la statistici necesită rolul DISPATCH sau ADMIN.");
        }
    }

    private static Instant now() {
        Instant now = Instant.now();
        return now.with(ChronoField.NANO_OF_SECOND, now.getNano() / 100 * 100);
    }

    private static Window window(String from, String to, Instant now, Duration lookback) {
        Instant end = to == null ? now : timestamp("to", to);
        Instant start = from == null ? end.minus(lookback) : timestamp("from", from);
        if (start.isBefore(MIN_DATETIME2) || !start.isBefore(end)) {
            throw invalid("from", "Începutul intervalului trebuie să fie valid și anterior sfârșitului.");
        }
        return new Window(start, end);
    }

    private static Instant timestamp(String parameter, String value) {
        try {
            Instant instant = OffsetDateTime.parse(value).toInstant();
            if (instant.isBefore(MIN_DATETIME2) || instant.isAfter(MAX_DATETIME2) || instant.getNano() % 100 != 0) {
                throw timestampError(parameter);
            }
            return instant;
        } catch (DateTimeParseException e) {
            throw timestampError(parameter);
        }
    }

    private static ApiException timestampError(String parameter) {
        return invalid(parameter, "Folosește o dată ISO-8601 cu fus orar, în intervalul SQL datetime2(7).");
    }

    private static int integer(String parameter, String value, int fallback, int max) {
        if (value == null) {
            return fallback;
        }
        try {
            int number = Integer.parseInt(value);
            if (number >= 1 && number <= max) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // A malformed integer and an out-of-range integer have the same validation response.
        }
        throw invalid(parameter, "Parametrul „" + parameter + "” trebuie să fie un număr întreg între 1 și " + max + ".");
    }

    private static String scenario(String parameter, String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (!SCENARIO.matcher(value).matches()) {
            throw invalid(parameter, "Eticheta scenariului trebuie să respecte ^[a-z0-9_-]{1,32}$.");
        }
        return value;
    }

    private static ApiException invalid(String parameter, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, null, Map.of("field", parameter));
    }

    private record Window(Instant from, Instant to) {
    }
}
