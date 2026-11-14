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
 * Curăță sesiunile de upload abandonate (inactive peste 24 h sau cu storage căzut peste 10 minute; abort în storage).
 * Rulează periodic pe fiecare instanță {@code api}, dar o singură execuție la un moment dat: lock-ul aplicației
 * {@code sp_getapplock} din SQL Server (legat de conexiune, deci se eliberează singur dacă instanța moare) joacă rolul
 * lease-ului din DB, fără tabelă nouă.
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
            log.warn("Curățarea upload-urilor a eșuat: {}", e.getMessage());
        }
    }

    /** @return câte sesiuni a abandonat, sau {@code -1} dacă altă instanță rulează deja jobul */
    int runExclusively() {
        return jdbc.execute((ConnectionCallback<Integer>) con -> {
            if (!lock(con, true)) {
                return -1;
            }
            try {
                int done = uploads.cleanup();
                if (done > 0) {
                    log.info("Curățare upload-uri: {} sesiuni abandonate", done);
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
