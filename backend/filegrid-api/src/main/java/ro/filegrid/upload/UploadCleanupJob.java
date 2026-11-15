package ro.filegrid.upload;

import java.sql.CallableStatement;
import java.sql.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Cleans up abandoned upload sessions (idle for more than 24 h, or with storage down for more than 10 minutes;
 * abort in storage). Runs periodically on every {@code api} instance but only one execution at a time: the SQL Server
 * application lock {@code sp_getapplock} (bound to the connection, so it is released if the instance dies) acts as
 * the DB lease, without a new table.
 */
@Configuration
@EnableScheduling
@Profile("db")
public class UploadCleanupJob {

    static final String LOCK_RESOURCE = "filegrid-upload-cleanup";
    private static final Logger log = LoggerFactory.getLogger(UploadCleanupJob.class);

    private final UploadService uploads;
    private final JdbcTemplate jdbc;

    public UploadCleanupJob(UploadService uploads, JdbcTemplate jdbc) {
        this.uploads = uploads;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${filegrid.uploads.cleanup-interval-ms:60000}",
            initialDelayString = "${filegrid.uploads.cleanup-initial-delay-ms:30000}")
    public void run() {
        try {
            runExclusively();
        } catch (RuntimeException e) {
            log.warn("Upload cleanup failed: {}", e.getMessage());
        }
    }

    /** @return how many sessions it abandoned, or {@code -1} if another instance is already running the job */
    int runExclusively() {
        return jdbc.execute((ConnectionCallback<Integer>) con -> {
            if (!lock(con, true)) {
                return -1;
            }
            try {
                int done = uploads.cleanup();
                if (done > 0) {
                    log.info("Upload cleanup: {} sessions abandoned", done);
                }
                return done;
            } finally {
                lock(con, false);
            }
        });
    }

    private static boolean lock(java.sql.Connection con, boolean acquire) throws java.sql.SQLException {
        String sql = acquire
                ? "{? = call sp_getapplock(@Resource = ?, @LockMode = 'Exclusive', @LockOwner = 'Session', @LockTimeout = 0)}"
                : "{? = call sp_releaseapplock(@Resource = ?, @LockOwner = 'Session')}";
        try (CallableStatement call = con.prepareCall(sql)) {
            call.registerOutParameter(1, Types.INTEGER);
            call.setString(2, LOCK_RESOURCE);
            call.execute();
            return call.getInt(1) >= 0;
        }
    }
}
