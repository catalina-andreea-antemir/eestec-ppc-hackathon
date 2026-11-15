package ro.filegrid.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import ro.filegrid.integrity.Sha256;

/**
 * Audit log ({@code dbo.audit_log}, FileGridMeta). Every row carries a hash chain
 * ({@code hash = SHA-256(prev_hash | id | ts | actor | action | file_id)}, {@code prev_hash} = the previous row's
 * {@code hash}): later tampering with or deleting a row breaks the chain. Writes are serialized with
 * {@code sp_getapplock} (session lock), so the chain stays correct across several app instances.
 */
@Repository
@Profile("db")
public class AuditLog {

    private static final int LOCK_TIMEOUT_MS = 10_000;
    private static final String LOCK_RESOURCE = "filegrid_audit_chain";

    private final NamedParameterJdbcTemplate jdbc;

    public AuditLog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends a row to the chain. The {@code sp_getapplock} lock (session scoped, released explicitly; also gone if the
     * connection drops) keeps reading the last hash and the insert together, on the same connection.
     */
    public void append(String actor, String action, UUID fileId) {
        jdbc.getJdbcOperations().execute((java.sql.Connection connection) -> {
            try (java.sql.CallableStatement lock = connection.prepareCall("{? = call sp_getapplock(?, ?, ?, ?)}")) {
                lock.registerOutParameter(1, Types.INTEGER);
                lock.setString(2, LOCK_RESOURCE);
                lock.setString(3, "Exclusive");
                lock.setString(4, "Session");
                lock.setInt(5, LOCK_TIMEOUT_MS);
                lock.execute();
                int result = lock.getInt(1);
                if (result < 0) {
                    throw new IllegalStateException("Lanțul de audit e blocat de altă scriere (sp_getapplock " + result + ")");
                }
            }
            try {
                insert(connection, actor, action, fileId);
            } finally {
                try (java.sql.CallableStatement unlock = connection.prepareCall("{? = call sp_releaseapplock(?, ?)}")) {
                    unlock.registerOutParameter(1, Types.INTEGER);
                    unlock.setString(2, LOCK_RESOURCE);
                    unlock.setString(3, "Session");
                    unlock.execute();
                } catch (java.sql.SQLException e) {
                    // the lock disappears anyway when the connection closes / is recycled
                }
            }
            return null;
        });
    }

    private void insert(java.sql.Connection connection, String actor, String action, UUID fileId)
            throws java.sql.SQLException {
        byte[] prev = null;
        String ts;
        try (java.sql.Statement st = connection.createStatement();
             java.sql.ResultSet tip = st.executeQuery(
                     "SELECT TOP 1 hash FROM dbo.audit_log WHERE hash IS NOT NULL ORDER BY ts DESC, id DESC")) {
            if (tip.next()) {
                prev = tip.getBytes(1);
            }
        }
        try (java.sql.Statement st = connection.createStatement();
             java.sql.ResultSet now = st.executeQuery("SELECT CONVERT(VARCHAR(27), SYSUTCDATETIME(), 121)")) {
            now.next();
            ts = now.getString(1);
        }
        UUID id = UUID.randomUUID();
        byte[] hash = hash(prev, id, ts, actor, action, fileId);
        try (java.sql.PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO dbo.audit_log (id, ts, actor, action, file_id, prev_hash, hash)
                VALUES (CAST(? AS UNIQUEIDENTIFIER), CAST(? AS DATETIME2(7)), ?, ?, CAST(? AS UNIQUEIDENTIFIER), ?, ?)""")) {
            insert.setString(1, id.toString());
            insert.setString(2, ts);
            insert.setString(3, actor);
            insert.setString(4, action);
            insert.setString(5, fileId == null ? null : fileId.toString());
            insert.setBytes(6, prev);
            insert.setBytes(7, hash);
            insert.executeUpdate();
        }
    }

    /** Walks the chain in order; returns the number of verified rows or throws at the first row that does not match. */
    public int verifyChain() {
        record Row(String id, String ts, String actor, String action, String fileId, byte[] prev, byte[] hash) {
        }
        List<Row> rows = jdbc.getJdbcOperations().query("""
                SELECT CONVERT(VARCHAR(36), id) AS id, CONVERT(VARCHAR(27), ts, 121) AS ts, actor, action,
                       CONVERT(VARCHAR(36), file_id) AS file_id, prev_hash, hash
                  FROM dbo.audit_log WHERE hash IS NOT NULL ORDER BY ts, id""",
                (r, n) -> new Row(r.getString("id"), r.getString("ts"), r.getString("actor"), r.getString("action"),
                        r.getString("file_id"), r.getBytes("prev_hash"), r.getBytes("hash")));
        byte[] expectedPrev = null;
        for (Row row : rows) {
            byte[] expected = hash(row.prev(), UUID.fromString(row.id()), row.ts(), row.actor(), row.action(),
                    row.fileId() == null ? null : UUID.fromString(row.fileId()));
            boolean linked = expectedPrev == null ? row.prev() == null : MessageDigest.isEqual(expectedPrev, row.prev());
            if (!linked || !MessageDigest.isEqual(expected, row.hash())) {
                throw new IllegalStateException("Lanțul de audit e rupt la rândul " + row.id());
            }
            expectedPrev = row.hash();
        }
        return rows.size();
    }

    public static byte[] hash(byte[] prev, UUID id, String ts, String actor, String action, UUID fileId) {
        MessageDigest digest = Sha256.newDigest();
        if (prev != null) {
            digest.update(prev);
        }
        String body = "|" + id.toString().toUpperCase() + "|" + ts + "|" + actor + "|" + action + "|"
                + (fileId == null ? "" : fileId.toString().toUpperCase());
        digest.update(body.getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }
}
