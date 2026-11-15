package ro.filegrid.observability;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code filegrid_meter_unconfirmed}: rândurile din {@code meter_readings} fără {@code file_id} mai vechi de 60 s
 * (citiri neconfirmate, alerta 1 / R1). Valoarea se reîmprospătează periodic din FileGridMeta, nu la fiecare scrape;
 * dacă baza nu răspunde, rămâne ultima valoare cunoscută.
 */
@Component
@Profile("db")
public class MeterUnconfirmedGauge {

    private static final Logger log = LoggerFactory.getLogger(MeterUnconfirmedGauge.class);
    static final String SQL = "SELECT COUNT_BIG(*) FROM dbo.meter_readings "
            + "WHERE file_id IS NULL AND received_at < DATEADD(second, -60, SYSUTCDATETIME())";

    private final JdbcTemplate jdbc;
    private final AtomicLong value = new AtomicLong();

    public MeterUnconfirmedGauge(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("filegrid.meter.unconfirmed", value, AtomicLong::get)
                .description("Citiri fără file_id mai vechi de 60 s").register(registry);
    }

    public long value() {
        return value.get();
    }

    @Scheduled(fixedDelayString = "${filegrid.opmetrics.unconfirmed-interval-ms:10000}", initialDelay = 5000)
    public void refresh() {
        try {
            Long count = jdbc.queryForObject(SQL, Long.class);
            value.set(count == null ? 0 : count);
        } catch (RuntimeException e) {
            log.warn("filegrid_meter_unconfirmed nu s-a putut actualiza: {}", e.getMessage());
        }
    }
}
