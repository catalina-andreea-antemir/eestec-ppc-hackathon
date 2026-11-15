package ro.filegrid.observability;

import java.time.Duration;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Measurements for {@code /stats/requirements}, from FileGridMeta (shared by every api instance): the windows
 * (R2-R4) are computed from {@code operation_metrics} ({@code source = 'live'} only) and {@code audit_log}.
 */
@Component
@Profile("db")
public class JdbcMeasurements implements RequirementsService.Measurements {

    private final JdbcTemplate jdbc;
    private final MeterUnconfirmedGauge unconfirmed;

    public JdbcMeasurements(JdbcTemplate jdbc, MeterUnconfirmedGauge unconfirmed) {
        this.jdbc = jdbc;
        this.unconfirmed = unconfirmed;
    }

    @Override
    public long meterUnconfirmed() {
        return unconfirmed.value();
    }

    @Override
    public RequirementsService.OutageLatency outageLatency(Duration window) {
        var rows = jdbc.queryForList("SELECT DISTINCT PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms) OVER () "
                + "AS p95, COUNT_BIG(*) OVER () AS n FROM dbo.operation_metrics WHERE category = N'OUTAGE_RECORD' "
                + "AND source = N'live' AND operation <> N'" + OperationMetrics.BULKHEAD_REJECTED + "' "
                + "AND ts >= DATEADD(second, -?, SYSUTCDATETIME())", window.toSeconds());
        if (rows.isEmpty()) {
            return new RequirementsService.OutageLatency(null, 0);
        }
        Object p95 = rows.getFirst().get("p95");
        return new RequirementsService.OutageLatency(p95 == null ? null : ((Number) p95).doubleValue() / 1000.0,
                ((Number) rows.getFirst().get("n")).longValue());
    }

    @Override
    public long documentAccessDenied(Duration window) {
        Long n = jdbc.queryForObject("SELECT COUNT_BIG(*) FROM dbo.audit_log WHERE action LIKE N'%[_]DENIED' "
                + "AND ts >= DATEADD(second, -?, SYSUTCDATETIME())", Long.class, window.toSeconds());
        return n == null ? 0 : n;
    }

    @Override
    public long[] videoTransfers(Duration window) {
        return jdbc.queryForObject("SELECT COUNT_BIG(*), COALESCE(SUM(CASE WHEN ok = 0 THEN 1 ELSE 0 END), 0) "
                + "FROM dbo.operation_metrics WHERE category = N'INSPECTION_VIDEO' AND source = N'live' "
                + "AND operation IN (N'upload', N'upload_chunk', N'upload_complete', N'download') "
                + "AND ts >= DATEADD(second, -?, SYSUTCDATETIME())",
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, window.toSeconds());
    }
}
