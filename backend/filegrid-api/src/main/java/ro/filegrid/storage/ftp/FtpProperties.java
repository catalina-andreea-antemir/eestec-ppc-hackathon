package ro.filegrid.storage.ftp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurația {@code filegrid.ftp} (vsftpd, C6), din variabilele {@code FTP_HOST}, {@code FTP_PORT}, {@code FTP_USER},
 * {@code FTP_PASSWORD} (setate în compose pentru containerul {@code api}). Fără valori implicite pentru credențiale:
 * dacă lipsesc, adaptorul pornește „neconfigurat” ({@code health} = down, operațiile → 503), iar aplicația pornește.
 *
 * @param host           numele sau adresa serverului FTP, de exemplu {@code ftp}
 * @param port           portul de control (implicit 21)
 * @param user           utilizatorul aplicației
 * @param password       parola utilizatorului aplicației
 * @param connectTimeout timeout de conectare TCP (implicit 2 s)
 * @param controlTimeout timeout de inactivitate pe conexiunea de control: răspunsurile la comenzi (implicit 30 s)
 * @param dataTimeout    timeout de inactivitate pe conexiunea de date: citire / scriere (implicit 30 s)
 * @param healthTimeout  timeout total pentru {@code health()} (implicit 2 s, maxim 2 s după contract)
 */
@ConfigurationProperties("filegrid.ftp")
public record FtpProperties(String host, Integer port, String user, String password, Duration connectTimeout,
        Duration controlTimeout, Duration dataTimeout, Duration healthTimeout) {

    static final Duration MAX_HEALTH_TIMEOUT = Duration.ofSeconds(2);

    public FtpProperties {
        port = port == null ? 21 : port;
        connectTimeout = positiveOr(connectTimeout, Duration.ofSeconds(2));
        controlTimeout = positiveOr(controlTimeout, Duration.ofSeconds(30));
        dataTimeout = positiveOr(dataTimeout, Duration.ofSeconds(30));
        healthTimeout = healthTimeout == null || healthTimeout.compareTo(MAX_HEALTH_TIMEOUT) > 0
                || healthTimeout.isNegative() || healthTimeout.isZero() ? MAX_HEALTH_TIMEOUT : healthTimeout;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("filegrid.ftp.port trebuie să fie între 1 și 65535");
        }
    }

    /** Adresa și credențialele pentru teste sau cod, cu restul valorilor implicite. */
    public static FtpProperties of(String host, int port, String user, String password) {
        return new FtpProperties(host, port, user, password, null, null, null, null);
    }

    /** {@code true} dacă sunt setate serverul, utilizatorul și parola. */
    public boolean isConfigured() {
        return missing().isEmpty();
    }

    /** Numele variabilelor lipsă (fără valori), pentru log și pentru {@code health}. */
    String missing() {
        StringBuilder sb = new StringBuilder();
        append(sb, host, "FTP_HOST");
        append(sb, user, "FTP_USER");
        append(sb, password, "FTP_PASSWORD");
        return sb.toString();
    }

    /** Fără parolă (record-ul implicit ar afișa-o). */
    @Override
    public String toString() {
        return "FtpProperties[host=" + host + ", port=" + port + "]";
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private static void append(StringBuilder sb, String value, String name) {
        if (value == null || value.isBlank()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(name);
        }
    }
}
