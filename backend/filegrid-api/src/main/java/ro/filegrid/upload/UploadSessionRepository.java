package ro.filegrid.upload;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.ChunkReceipt;

/**
 * Starea upload-urilor în bucăți (FileGridMeta: {@code upload_sessions}, {@code upload_chunks}). Toate comparațiile de
 * timp (lease, inactivitate) folosesc ceasul SQL Server ({@code SYSUTCDATETIME()}), ca instanțele {@code api} cu ceasuri
 * diferite să se înțeleagă. Tranzacțiile le deschide serviciul.
 */
@Repository
@Profile("db")
public class UploadSessionRepository {

    private static final String COLUMNS = """
            CONVERT(NVARCHAR(36), upload_id) AS id, category, storage_type, storage_upload_ref, original_filename,
            content_type, chunk_size, total_size, status, next_chunk, created_at, updated_at,
            storage_unavailable_since, created_by""";

    private static final RowMapper<UploadSession> SESSION = (rs, n) -> new UploadSession(
            UUID.fromString(rs.getString("id")), Category.valueOf(rs.getString("category")),
            StorageType.valueOf(rs.getString("storage_type")), rs.getString("storage_upload_ref"),
            rs.getString("original_filename"), rs.getString("content_type"), rs.getInt("chunk_size"),
            rs.getLong("total_size"), rs.getString("status"), rs.getInt("next_chunk"),
            instant(rs.getObject("created_at", LocalDateTime.class)),
            instant(rs.getObject("updated_at", LocalDateTime.class)),
            instant(rs.getObject("storage_unavailable_since", LocalDateTime.class)), rs.getString("created_by"));

    private static final RowMapper<Chunk> CHUNK = (rs, i) ->
            new Chunk(rs.getInt("n"), rs.getLong("size_bytes"), rs.getBytes("sha256"), rs.getString("etag"));

    private final NamedParameterJdbcTemplate jdbc;

    public UploadSessionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UploadSession s) {
        jdbc.update("""
                INSERT INTO dbo.upload_sessions (upload_id, category, storage_type, storage_upload_ref,
                    original_filename, content_type, chunk_size, total_size, status, next_chunk, created_by)
                VALUES (CAST(:id AS UNIQUEIDENTIFIER), :category, :storage, :ref, :name, :contentType, :chunkSize,
                    :totalSize, N'PENDING', 1, :createdBy)""", id(s.id())
                .addValue("category", s.category().name(), Types.NVARCHAR)
                .addValue("storage", s.storage().name(), Types.NVARCHAR)
                .addValue("ref", s.uploadRef(), Types.NVARCHAR)
                .addValue("name", s.fileName(), Types.NVARCHAR)
                .addValue("contentType", s.contentType(), Types.NVARCHAR)
                .addValue("chunkSize", s.chunkSize(), Types.INTEGER)
                .addValue("totalSize", s.totalSize(), Types.BIGINT)
                .addValue("createdBy", s.createdBy(), Types.NVARCHAR));
    }

    public Optional<UploadSession> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM dbo.upload_sessions WHERE upload_id = CAST(:id AS UNIQUEIDENTIFIER)",
                id(id), SESSION).stream().findFirst();
    }

    /** Lease per upload: {@code UPDATE} atomic cu condiție; {@code true} dacă acum îl deținem noi. */
    public boolean tryAcquireLease(UUID id, String owner, int leaseSeconds) {
        return jdbc.update("""
                UPDATE dbo.upload_sessions
                   SET lease_owner = :owner, lease_until = DATEADD(SECOND, :secs, SYSUTCDATETIME())
                 WHERE upload_id = CAST(:id AS UNIQUEIDENTIFIER) AND status = N'PENDING'
                   AND (lease_owner IS NULL OR lease_until < SYSUTCDATETIME())""",
                id(id).addValue("owner", owner, Types.NVARCHAR).addValue("secs", leaseSeconds, Types.INTEGER)) == 1;
    }

    /** Prelungește lease-ul cât timp cererea încă lucrează (bucată lentă, hash final pe un fișier mare). */
    public boolean renewLease(UUID id, String owner, int leaseSeconds) {
        return jdbc.update("""
                UPDATE dbo.upload_sessions SET lease_until = DATEADD(SECOND, :secs, SYSUTCDATETIME())
                 WHERE upload_id = CAST(:id AS UNIQUEIDENTIFIER) AND lease_owner = :owner""",
                id(id).addValue("owner", owner, Types.NVARCHAR).addValue("secs", leaseSeconds, Types.INTEGER)) == 1;
    }

    public void releaseLease(UUID id, String owner) {
        jdbc.update("""
                UPDATE dbo.upload_sessions SET lease_owner = NULL, lease_until = NULL
                 WHERE upload_id = CAST(:id AS UNIQUEIDENTIFIER) AND lease_owner = :owner""",
                id(id).addValue("owner", owner, Types.NVARCHAR));
    }

    /**
     * Înregistrează bucata și avansează {@code next_chunk}, doar dacă lease-ul e încă al nostru și
     * {@code next_chunk = n}. {@code false} → condiția nu mai e adevărată (apelantul face rollback).
     */
    public boolean recordChunk(UUID id, String owner, ChunkReceipt receipt, byte[] sha256) {
        int advanced = jdbc.update("""
                UPDATE dbo.upload_sessions
                   SET next_chunk = :next, updated_at = SYSUTCDATETIME(), storage_unavailable_since = NULL
                 WHERE upload_id = CAST(:id AS UNIQUEIDENTIFIER) AND status = N'PENDING' AND lease_owner = :owner
                   AND next_chunk = :n""",
                id(id).addValue("owner", owner, Types.NVARCHAR).addValue("n", receipt.n(), Types.INTEGER)
                        .addValue("next", receipt.n() + 1, Types.INTEGER));
        if (advanced != 1) {
            return false;
        }
        jdbc.update("""
                INSERT INTO dbo.upload_chunks (upload_id, n, size_bytes, sha256, etag)
                VALUES (CAST(:id AS UNIQUEIDENTIFIER), :n, :size, :sha, :etag)""",
                id(id).addValue("n", receipt.n(), Types.INTEGER).addValue("size", receipt.size(), Types.INTEGER)
                        .addValue("sha", sha256, Types.VARBINARY).addValue("etag", receipt.etag(), Types.NVARCHAR));
        return true;
    }

    public Optional<Chunk> findChunk(UUID id, int n) {
        return jdbc.query("""
                SELECT n, size_bytes, sha256, etag FROM dbo.upload_chunks
                 WHERE upload_id = CAST(:id AS UNIQUEIDENTIFIER) AND n = :n""",
                id(id).addValue("n", n, Types.INTEGER), CHUNK).stream().findFirst();
    }

    /** Bucățile primite, în ordine. */
    public List<Chunk> chunks(UUID id) {
        return jdbc.query("""
                SELECT n, size_bytes, sha256, etag FROM dbo.upload_chunks
                 WHERE upload_id = CAST(:id AS UNIQUEIDENTIFIER) ORDER BY n""", id(id), CHUNK);
    }

    public long receivedBytes(UUID id) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CAST(size_bytes AS BIGINT)), 0) FROM dbo.upload_chunks
                 WHERE upload_id = CAST(:id AS UNIQUEIDENTIFIER)""", id(id), Long.class);
        return sum == null ? 0 : sum;
    }

    /** Marchează începutul unei căderi de storage (păstrează momentul primei căderi). */
    public void markStorageUnavailable(UUID id) {
        jdbc.update("""
                UPDATE dbo.upload_sessions
                   SET storage_unavailable_since = COALESCE(storage_unavailable_since, SYSUTCDATETIME())
                 WHERE upload_id = CAST(:id AS UNIQUEIDENTIFIER) AND status = N'PENDING'""", id(id));
    }

    /** Schimbă starea unei sesiuni {@code PENDING} și eliberează lease-ul. */
    public boolean finish(UUID id, String status) {
        return jdbc.update("""
                UPDATE dbo.upload_sessions SET status = :status, lease_owner = NULL, lease_until = NULL,
                       updated_at = SYSUTCDATETIME()
                 WHERE upload_id = CAST(:id AS UNIQUEIDENTIFIER) AND status = N'PENDING'""",
                id(id).addValue("status", status, Types.NVARCHAR)) == 1;
    }

    /** Sesiunile de abandonat: inactive peste {@code inactiveSeconds} sau cu storage căzut peste {@code downSeconds}. */
    public List<UUID> abandonable(long inactiveSeconds, long downSeconds, int limit) {
        return jdbc.query("""
                SELECT TOP (:limit) CONVERT(NVARCHAR(36), upload_id) FROM dbo.upload_sessions
                 WHERE status = N'PENDING'
                   AND (updated_at < DATEADD(SECOND, -:inactive, SYSUTCDATETIME())
                        OR storage_unavailable_since < DATEADD(SECOND, -:down, SYSUTCDATETIME()))
                 ORDER BY updated_at""",
                new MapSqlParameterSource().addValue("limit", limit).addValue("inactive", inactiveSeconds)
                        .addValue("down", downSeconds), (rs, n) -> UUID.fromString(rs.getString(1)));
    }

    public record Chunk(int n, long size, byte[] sha256, String etag) {
        public ChunkReceipt receipt() {
            return new ChunkReceipt(n, size, etag);
        }
    }

    private static MapSqlParameterSource id(UUID id) {
        return new MapSqlParameterSource().addValue("id", id.toString(), Types.VARCHAR);
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
