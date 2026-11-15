package ro.filegrid.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/health}, unauthenticated (contract {@code getHealth}): {@code {"status":"UP"}} when api and
 * FileGridMeta respond, otherwise 503 {@code {"status":"DOWN"}} with {@code Retry-After}. Used by the api and nginx
 * container healthchecks and the start scripts. Per-storage state lives at {@code /admin/health} (C11).
 *
 * <p>FileGridMeta is checked with {@link Connection#isValid(int)} (2 s) and the result is reused for 1 s, so a burst
 * of requests on this public endpoint cannot exhaust the JDBC pool. Without a configured database (default profile,
 * no {@code db}) it reports UP as long as the app is running.
 */
@RestController
public class HealthController {

    static final int RETRY_AFTER_SECONDS = 5;
    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private static final Duration CACHE = Duration.ofSeconds(1);
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final ObjectProvider<DataSource> metaDataSource;
    private final Clock clock;
    private volatile Check last;

    @Autowired
    public HealthController(ObjectProvider<DataSource> metaDataSource) {
        this(metaDataSource, Clock.systemUTC());
    }

    HealthController(ObjectProvider<DataSource> metaDataSource, Clock clock) {
        this.metaDataSource = metaDataSource;
        this.clock = clock;
    }

    @GetMapping("/api/v1/health")
    public ResponseEntity<Health> health() {
        if (metaDatabaseUp()) {
            return ResponseEntity.ok(new Health("UP"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(RETRY_AFTER_SECONDS))
                .body(new Health("DOWN"));
    }

    boolean metaDatabaseUp() {
        Instant now = clock.instant();
        Check cached = last;
        if (cached != null && Duration.between(cached.at(), now).compareTo(CACHE) < 0) {
            return cached.up();
        }
        boolean up = checkMetaDatabase();
        last = new Check(clock.instant(), up);
        return up;
    }

    private boolean checkMetaDatabase() {
        DataSource dataSource = metaDataSource.getIfAvailable();
        if (dataSource == null) {
            return true;
        }
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                return true;
            }
            log.warn("Health: FileGridMeta is not responding (invalid connection)");
        } catch (SQLException | RuntimeException e) {
            log.warn("Health: FileGridMeta is not responding: {}", e.getMessage());
        }
        return false;
    }

    public record Health(String status) {
    }

    private record Check(Instant at, boolean up) {
    }
}
