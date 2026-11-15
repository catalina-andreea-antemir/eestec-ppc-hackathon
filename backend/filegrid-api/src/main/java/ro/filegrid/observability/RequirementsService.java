package ro.filegrid.observability;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import ro.filegrid.domain.Category;
import ro.filegrid.ingest.DocumentEncryption;
import ro.filegrid.policy.PolicyEngine;

/** Starea celor 4 cerințe de business (R1–R4) față de pragurile din {@code requirements.yml}. */
@Service
@Profile("db")
public class RequirementsService {

    /** Valorile măsurate; implementarea de producție citește FileGridMeta, testele dau valori fixe. */
    public interface Measurements {
        long meterUnconfirmed();

        /** p95 al latenței operațiilor pe avarii în fereastră; {@code samples = 0} și p95 {@code null} fără măsurători. */
        OutageLatency outageLatency(Duration window);

        long documentAccessDenied(Duration window);

        /** Transferuri video (încărcări, bucăți, descărcări) în fereastră: {total, eșuate}. */
        long[] videoTransfers(Duration window);
    }

    public record OutageLatency(Double p95Seconds, long samples) {
    }

    /** Pragurile din {@code requirements.yml}. */
    public record Thresholds(Duration window, long minSamples, long unconfirmedMax, double p95MaxSeconds,
                             long deniedMax, double failedMaxPercent) {
        public static Thresholds load() {
            PropertySource<?> p;
            try {
                p = new YamlPropertySourceLoader().load("requirements", new ClassPathResource("requirements.yml"))
                        .getFirst();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("requirements.yml nu poate fi citit", e);
            }
            return new Thresholds(
                    org.springframework.boot.convert.DurationStyle.detectAndParse(str(p, "window")),
                    Long.parseLong(str(p, "minSamples")),
                    Long.parseLong(str(p, "readings.unconfirmedMax")),
                    Double.parseDouble(str(p, "outages.p95MaxSeconds")),
                    Long.parseLong(str(p, "documents.deniedMax")),
                    Double.parseDouble(str(p, "videos.failedMaxPercent")));
        }

        private static String str(PropertySource<?> p, String key) {
            Object v = p.getProperty(key);
            if (v == null) {
                throw new IllegalStateException("requirements.yml: lipsește " + key);
            }
            return v.toString();
        }
    }

    public record Requirement(String id, Category category, String title, String status, String message,
                              Map<String, Object> thresholds, Map<String, Object> current) {
    }

    public record Requirements(Instant generatedAt, List<Requirement> requirements) {
    }

    static final String OK = "OK";
    static final String AT_RISK = "AT_RISK";

    private final Measurements measurements;
    private final PolicyEngine policies;
    private final DocumentEncryption encryption;
    private final Thresholds thresholds;

    @org.springframework.beans.factory.annotation.Autowired
    public RequirementsService(Measurements measurements, PolicyEngine policies, DocumentEncryption encryption) {
        this(measurements, policies, encryption, Thresholds.load());
    }

    public RequirementsService(Measurements measurements, PolicyEngine policies, DocumentEncryption encryption,
                               Thresholds thresholds) {
        this.measurements = measurements;
        this.policies = policies;
        this.encryption = encryption;
        this.thresholds = thresholds;
    }

    public Requirements evaluate() {
        Duration w = thresholds.window();
        return new Requirements(Instant.now().truncatedTo(ChronoUnit.SECONDS),
                List.of(readings(), outages(w), documents(w), videos(w)));
    }

    private Requirement readings() {
        long unconfirmed = measurements.meterUnconfirmed();
        boolean ok = unconfirmed <= thresholds.unconfirmedMax();
        return new Requirement("R1", Category.METER_READING, "Citiri de contor confirmate", status(ok),
                ok ? "Nicio citire neconfirmată peste 60 s."
                        : unconfirmed + " citiri fără confirmare de peste 60 s.",
                map("unconfirmedMax", thresholds.unconfirmedMax()),
                map("unconfirmed", unconfirmed));
    }

    /**
     * R2 și R4: cu mai puține măsurători decât {@code minSamples} datele sunt insuficiente și statusul rămâne {@code OK}
     * (cu mesaj explicit); cu suficiente date, o depășire a pragului rămâne {@code AT_RISK}.
     */
    private Requirement outages(Duration w) {
        OutageLatency latency = measurements.outageLatency(w);
        long samples = latency.samples();
        boolean sufficient = samples >= thresholds.minSamples();
        boolean ok = !sufficient || latency.p95Seconds() < thresholds.p95MaxSeconds();
        String message;
        if (samples == 0) {
            message = "Cerința nu a putut fi verificată: nicio cerere pentru avarii în ultimele " + minutes(w)
                    + " minute.";
        } else if (!sufficient) {
            message = "Date insuficiente: " + samples + " din minim " + thresholds.minSamples()
                    + " cereri pentru avarii în ultimele " + minutes(w) + " minute.";
        } else {
            message = "p95 = " + round(latency.p95Seconds()) + " s în ultimele " + minutes(w) + " minute (prag "
                    + thresholds.p95MaxSeconds() + " s).";
        }
        return new Requirement("R2", Category.OUTAGE_RECORD, "Avarii sub 2 secunde (p95)", status(ok), message,
                map("p95MaxSeconds", thresholds.p95MaxSeconds(), "windowMinutes", minutes(w),
                        "minSamples", thresholds.minSamples()),
                map("p95Seconds", samples == 0 || latency.p95Seconds() == null ? null : round(latency.p95Seconds()),
                        "samples", samples, "sufficientData", sufficient));
    }

    private Requirement documents(Duration w) {
        boolean encrypted = policies.policy(Category.CONNECTION_DOC).encrypted() && encryption != null;
        long denied = measurements.documentAccessDenied(w);
        boolean ok = encrypted && denied <= thresholds.deniedMax();
        String message = !encrypted ? "Criptarea documentelor nu este activă."
                : denied > thresholds.deniedMax() ? denied + " accesuri refuzate în ultimele " + minutes(w) + " minute."
                : "Criptare activă, niciun acces refuzat în ultimele " + minutes(w) + " minute.";
        return new Requirement("R3", Category.CONNECTION_DOC, "Documente protejate", status(ok), message,
                map("encryptionRequired", true, "deniedMax", thresholds.deniedMax(), "windowMinutes", minutes(w)),
                map("encryptionActive", encrypted, "denied", denied));
    }

    private Requirement videos(Duration w) {
        long[] t = measurements.videoTransfers(w);
        long samples = t[0];
        boolean sufficient = samples >= thresholds.minSamples();
        double percent = samples == 0 ? 0 : 100.0 * t[1] / samples;
        boolean ok = !sufficient || percent < thresholds.failedMaxPercent();
        String message;
        if (samples == 0) {
            message = "Cerința nu a putut fi verificată: niciun transfer video în ultimele " + minutes(w) + " minute.";
        } else if (!sufficient) {
            message = "Date insuficiente: " + samples + " din minim " + thresholds.minSamples()
                    + " transferuri video în ultimele " + minutes(w) + " minute.";
        } else {
            message = t[1] + " transferuri eșuate din " + samples + " (" + round(percent) + "%), prag "
                    + thresholds.failedMaxPercent() + "%.";
        }
        return new Requirement("R4", Category.INSPECTION_VIDEO, "Transferuri video reușite", status(ok), message,
                map("failedMaxPercent", thresholds.failedMaxPercent(), "windowMinutes", minutes(w),
                        "minSamples", thresholds.minSamples()),
                map("failedPercent", samples == 0 ? null : round(percent), "transfers", samples, "failed", t[1],
                        "samples", samples, "sufficientData", sufficient));
    }

    private static String status(boolean ok) {
        return ok ? OK : AT_RISK;
    }

    private static long minutes(Duration w) {
        return w.toMinutes();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
