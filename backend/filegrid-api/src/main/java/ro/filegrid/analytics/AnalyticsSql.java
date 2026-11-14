package ro.filegrid.analytics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import ro.filegrid.analytics.StatsResponses.CostAssumption;

/** Loads the packaged O6 scripts and substitutes only their parameter declarations. */
final class AnalyticsSql {

    static final String ANOMALIES = load("01_meter_consumption_anomalies.sql", "to_utc", "from_utc", "page", "page_size");
    static final String SILENT_METERS = load("02_silent_meters.sql", "as_of_utc", "silent_minutes");
    static final String CONSUMPTION_BY_HOUR = load("03_consumption_by_zone_hour.sql", "to_utc", "from_utc");
    static final String OUTAGE_ZONES = load("04_outages_by_zone.sql", "to_utc", "from_utc");
    static final String PEAK_VS_NORMAL = load("05_peak_vs_normal.sql", "normal_scenario", "peak_scenario",
            "normal_to_utc", "normal_from_utc", "peak_to_utc", "peak_from_utc");
    static final String STORAGE_ADVISOR = load("06_storage_recommendations.sql", "to_utc", "from_utc",
            "operation", "ftp_cost_per_gb", "s3_cost_per_gb", "db_cost_per_gb");
    static final CostAssumption COSTS = loadCosts();

    private AnalyticsSql() {
    }

    private static String load(String file, String... parameters) {
        try {
            String sql = new ClassPathResource("analytics/" + file).getContentAsString(StandardCharsets.UTF_8);
            // Keep placeholder order explicit and reject changes to the O6 declaration order.
            int nextDeclaration = 0;
            for (String parameter : parameters) {
                var declaration = Pattern.compile("(?m)^(DECLARE @" + parameter + "\\b[^=;]+?= )[^;]+;").matcher(sql);
                if (!declaration.find(nextDeclaration)) {
                    throw new IllegalStateException("Missing or out-of-order declaration @" + parameter + " in " + file);
                }
                int start = declaration.end(1);
                sql = sql.substring(0, start) + "?;" + sql.substring(declaration.end());
                nextDeclaration = start + 2;
            }
            return sql;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CostAssumption loadCosts() {
        try {
            var source = new YamlPropertySourceLoader()
                    .load("analytics-storage-costs", new ClassPathResource("analytics/storage-costs.yml")).getFirst();
            return new CostAssumption("cost relativ per GB (FTP = 1,0)",
                    new BigDecimal(source.getProperty("relativeCostPerGb.FTP").toString()),
                    new BigDecimal(source.getProperty("relativeCostPerGb.S3").toString()),
                    new BigDecimal(source.getProperty("relativeCostPerGb.DB").toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
