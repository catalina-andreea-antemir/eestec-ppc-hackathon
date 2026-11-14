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
 * {@code GET /api/v1/health}, fără autentificare (contract: {@code getHealth}): {@code {"status":"UP"}} când api și
 * FileGridMeta răspund, altfel 503 {@code {"status":"DOWN"}} cu {@code Retry-After}. Îl folosesc healthcheck-ul
 * containerelor api și nginx și scripturile de start. Starea fiecărui storage este la {@code /admin/health} (C11).
 *
 * <p>FileGridMeta se verifică cu {@link Connection#isValid(int)} (2 s), iar rezultatul se refolosește 1 s, ca un val
 * de cereri pe acest endpoint public să nu ocupe pool-ul JDBC. Fără baza de date configurată (profilul implicit, fără
 * {@code db}) răspunde UP dacă aplicația rulează.
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
            log.warn("Health: FileGridMeta nu răspunde (conexiune invalidă)");
        } catch (SQLException | RuntimeException e) {
            log.warn("Health: FileGridMeta nu răspunde: {}", e.getMessage());
        }
        return false;
    }

    public record Health(String status) {
    }

    private record Check(Instant at, boolean up) {
    }
}
