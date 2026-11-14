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
 * Metadatele fișierelor în FileGridMeta: {@code files} și {@code outage_records} (C7). Rândul se scrie abia după
 * confirmarea storage-ului, direct cu stare {@code CONFIRMED}; tranzacția o deschide apelantul.
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

    /** Inserează rândul din {@code files} și, la avarii, pe cel din {@code outage_records}. */
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

    /** Rândul din {@code files} (fără detaliile de avarie), pentru {@code complete} repetat la upload în bucăți. */
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

    private static MapSqlParameterSource id(UUID id) {
        return new MapSqlParameterSource().addValue("id", id.toString(), Types.VARCHAR);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
