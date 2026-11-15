package ro.filegrid.ingest;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * File metadata in FileGridMeta: {@code files} and {@code outage_records} (C7). The row is written only after
 * storage confirmed the write, directly as {@code CONFIRMED}; the caller owns the transaction.
 */
@Repository
@Profile("db")
public class FileMetadataRepository {

    private static final String INSERT_FILE = """
            INSERT INTO dbo.files (id, category, original_filename, content_type, size_bytes, sha256, sha256_plain,
                                   storage_type, storage_key, status, encrypted, created_at, created_by)
            VALUES (CAST(:id AS UNIQUEIDENTIFIER), :category, :originalName, :contentType, :size, :sha256,
                    :sha256Plain, :storage, :storageKey, :status, :encrypted, :createdAt, :createdBy)
            """;
    private static final String INSERT_OUTAGE = """
            INSERT INTO dbo.outage_records (file_id, zone, station_id, occurred_at)
            VALUES (CAST(:id AS UNIQUEIDENTIFIER), :zone, :stationId, :occurredAt)
            """;
    private static final String FIND_SHA256 = """
            SELECT sha256 FROM dbo.files WHERE id = CAST(:id AS UNIQUEIDENTIFIER)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public FileMetadataRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the {@code files} row and, for outages, the {@code outage_records} row. */
    public void insert(FileRecord file) {
        jdbc.update(INSERT_FILE, id(file.id())
                .addValue("category", file.category().name(), Types.NVARCHAR)
                .addValue("originalName", file.originalName(), Types.NVARCHAR)
                .addValue("contentType", file.contentType(), Types.NVARCHAR)
                .addValue("size", file.size(), Types.BIGINT)
                .addValue("sha256", file.sha256(), Types.VARBINARY)
                .addValue("sha256Plain", file.sha256Plain(), Types.VARBINARY)
                .addValue("storage", file.storage().name(), Types.NVARCHAR)
                .addValue("storageKey", file.storageKey(), Types.NVARCHAR)
                .addValue("status", file.status(), Types.NVARCHAR)
                .addValue("encrypted", file.encrypted(), Types.BIT)
                .addValue("createdAt", utc(file.createdAt()), Types.TIMESTAMP)
                .addValue("createdBy", file.createdBy(), Types.NVARCHAR));
        if (file.outage() != null) {
            jdbc.update(INSERT_OUTAGE, id(file.id())
                    .addValue("zone", file.outage().zone(), Types.NVARCHAR)
                    .addValue("stationId", file.outage().stationId(), Types.NVARCHAR)
                    .addValue("occurredAt", utc(file.outage().occurredAt()), Types.TIMESTAMP));
        }
    }

    public Optional<byte[]> findSha256(UUID fileId) {
        List<byte[]> rows = jdbc.query(FIND_SHA256, id(fileId), (row, n) -> row.getBytes(1));
        return rows.stream().findFirst();
    }

    /** The {@code files} row (without outage details), for a repeated {@code complete} on chunked uploads. */
    public Optional<FileRecord> find(UUID fileId) {
        return jdbc.query("""
                SELECT category, original_filename, content_type, size_bytes, sha256, sha256_plain, storage_type,
                       storage_key, status, encrypted, created_at, created_by
                  FROM dbo.files WHERE id = CAST(:id AS UNIQUEIDENTIFIER)""", id(fileId),
                (row, n) -> new FileRecord(fileId, ro.filegrid.domain.Category.valueOf(row.getString("category")),
                        row.getString("original_filename"), row.getString("content_type"), row.getLong("size_bytes"),
                        row.getBytes("sha256"), row.getBytes("sha256_plain"),
                        ro.filegrid.domain.StorageType.valueOf(row.getString("storage_type")),
                        row.getString("storage_key"), row.getString("status"), row.getBoolean("encrypted"),
                        row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        row.getString("created_by"), null)).stream().findFirst();
    }

    private static final String SELECT_WITH_OUTAGE = """
            SELECT CONVERT(NVARCHAR(36), f.id) AS id, f.category, f.original_filename, f.content_type, f.size_bytes,
                   f.sha256, f.sha256_plain, f.storage_type, f.storage_key, f.status, f.encrypted, f.created_at,
                   f.created_by, o.zone, o.station_id, o.occurred_at
              FROM dbo.files f LEFT JOIN dbo.outage_records o ON o.file_id = f.id
            """;

    /** The {@code files} row with outage details (C8: {@code GET /files/{id}}); any status. */
    public Optional<FileRecord> findWithOutage(UUID fileId) {
        return jdbc.query(SELECT_WITH_OUTAGE + " WHERE f.id = CAST(:id AS UNIQUEIDENTIFIER)", id(fileId),
                FileMetadataRepository::withOutage).stream().findFirst();
    }

    /**
     * {@code GET /files} search (C8): {@code CONFIRMED} files only, newest first. {@code q} (substring of the file
     * name) does not apply to {@code CONNECTION_DOC}: their names are encrypted, so documents bypass the {@code q} filter.
     */
    public FileSearch.Result search(FileSearch s) {
        if (s.categories().isEmpty()) {
            return new FileSearch.Result(List.of(), 0);
        }
        StringBuilder where = new StringBuilder(" WHERE f.status = N'CONFIRMED' AND f.category IN (:categories)");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("categories",
                s.categories().stream().map(Enum::name).toList());
        if (s.category() != null) {
            where.append(" AND f.category = :category");
            p.addValue("category", s.category().name(), Types.NVARCHAR);
        }
        if (s.q() != null && !s.q().isEmpty()) {
            where.append(" AND (f.category = N'CONNECTION_DOC' OR f.original_filename LIKE :q ESCAPE '\\')");
            p.addValue("q", "%" + s.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                    .replace("[", "\\[") + "%", Types.NVARCHAR);
        }
        if (s.from() != null) {
            where.append(" AND f.created_at >= :from");
            p.addValue("from", utc(s.from()), Types.TIMESTAMP);
        }
        if (s.to() != null) {
            where.append(" AND f.created_at < :to");
            p.addValue("to", utc(s.to()), Types.TIMESTAMP);
        }
        Long total = jdbc.queryForObject("SELECT COUNT_BIG(*) FROM dbo.files f" + where, p, Long.class);
        p.addValue("offset", (long) (s.page() - 1) * s.size(), Types.BIGINT).addValue("size", s.size(), Types.INTEGER);
        List<FileRecord> items = jdbc.query(SELECT_WITH_OUTAGE + where
                + " ORDER BY f.created_at DESC, f.id OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", p,
                FileMetadataRepository::withOutage);
        return new FileSearch.Result(items, total == null ? 0 : total);
    }

    private static FileRecord withOutage(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        String zone = row.getString("zone");
        FileRecord.OutageInfo outage = zone == null ? null : new FileRecord.OutageInfo(zone,
                row.getString("station_id"), row.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
        return new FileRecord(UUID.fromString(row.getString("id")),
                ro.filegrid.domain.Category.valueOf(row.getString("category")), row.getString("original_filename"),
                row.getString("content_type"), row.getLong("size_bytes"), row.getBytes("sha256"),
                row.getBytes("sha256_plain"), ro.filegrid.domain.StorageType.valueOf(row.getString("storage_type")),
                row.getString("storage_key"), row.getString("status"), row.getBoolean("encrypted"),
                row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                row.getString("created_by"), outage);
    }

    private static MapSqlParameterSource id(UUID id) {
        return new MapSqlParameterSource().addValue("id", id.toString(), Types.VARCHAR);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
