package ro.filegrid.storage.s3;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Configurația {@code filegrid.s3} (MinIO, C5), din variabilele {@code S3_ENDPOINT}, {@code S3_REGION},
 * {@code S3_BUCKET}, {@code S3_ACCESS_KEY}, {@code S3_SECRET_KEY} (setate în compose; credențialele sunt ale
 * utilizatorului aplicației creat de {@code minio-init}, nu contul root). Fără valori implicite pentru credențiale:
 * dacă lipsesc, adaptorul pornește „neconfigurat” ({@code health} = down, operațiile → 503), iar aplicația pornește.
 *
 * @param endpoint         URL-ul S3, de exemplu {@code http://minio:9000} (path-style)
 * @param region           regiunea de semnare (MinIO o acceptă pe oricare; implicit {@code us-east-1})
 * @param bucket           bucket-ul (creat de {@code minio-init})
 * @param accessKey        cheia de acces a utilizatorului aplicației
 * @param secretKey        cheia secretă a utilizatorului aplicației
 * @param connectTimeout   timeout de conectare TCP (implicit 2 s)
 * @param socketTimeout    timeout de inactivitate la citire / scriere pe socket (implicit 30 s)
 * @param operationTimeout timeout total pentru operațiile mici: HEAD, DELETE, creare / încheiere / abandon multipart
 *                         (implicit 10 s); transferurile de date au doar {@code socketTimeout}
 * @param healthTimeout    timeout total pentru {@code health()} (implicit 2 s, maxim 2 s după contract)
 * @param partSize         mărimea părților la {@code put} pentru obiecte mari, care trec intern prin multipart
 *                         (implicit 16 MiB, minim 5 MiB, limita S3 / MinIO)
 * @param maxConnections   conexiuni HTTP maxime în pool (implicit 50)
 */
@ConfigurationProperties("filegrid.s3")
public record S3Properties(String endpoint, String region, String bucket, String accessKey, String secretKey,
        Duration connectTimeout, Duration socketTimeout, Duration operationTimeout, Duration healthTimeout,
        DataSize partSize, Integer maxConnections) {

    static final Duration MAX_HEALTH_TIMEOUT = Duration.ofSeconds(2);

    public S3Properties {
        region = isBlank(region) ? "us-east-1" : region.trim();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        socketTimeout = socketTimeout == null ? Duration.ofSeconds(30) : socketTimeout;
        operationTimeout = operationTimeout == null ? Duration.ofSeconds(10) : operationTimeout;
        healthTimeout = healthTimeout == null || healthTimeout.compareTo(MAX_HEALTH_TIMEOUT) > 0
                ? MAX_HEALTH_TIMEOUT : healthTimeout;
        partSize = partSize == null ? DataSize.ofMegabytes(16) : partSize;
        maxConnections = maxConnections == null ? 50 : maxConnections;
        if (partSize.toBytes() < S3Adapter.MIN_PART_SIZE) {
            throw new IllegalArgumentException("filegrid.s3.part-size trebuie să fie de cel puțin 5 MiB");
        }
    }

    /** Credențiale și adrese pentru teste sau cod, cu restul valorilor implicite. */
    public static S3Properties of(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        return new S3Properties(endpoint, region, bucket, accessKey, secretKey, null, null, null, null, null, null);
    }

    /** {@code true} dacă sunt setate endpoint-ul, bucket-ul și ambele chei. */
    public boolean isConfigured() {
        return missing().isEmpty();
    }

    /** Numele variabilelor lipsă (fără valori), pentru log și pentru {@code health}. */
    String missing() {
        StringBuilder sb = new StringBuilder();
        append(sb, endpoint, "S3_ENDPOINT");
        append(sb, bucket, "S3_BUCKET");
        append(sb, accessKey, "S3_ACCESS_KEY");
        append(sb, secretKey, "S3_SECRET_KEY");
        return sb.toString();
    }

    /** Fără cheia secretă (record-ul implicit ar afișa-o). */
    @Override
    public String toString() {
        return "S3Properties[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + "]";
    }

    private static void append(StringBuilder sb, String value, String name) {
        if (isBlank(value)) {
            sb.append(sb.isEmpty() ? "" : ", ").append(name);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
