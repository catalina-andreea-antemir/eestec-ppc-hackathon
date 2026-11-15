package ro.filegrid.storage.ftp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code filegrid.ftp} configuration (vsftpd, C6), from {@code FTP_HOST}, {@code FTP_PORT}, {@code FTP_USER},
 * {@code FTP_PASSWORD} (set in compose for the {@code api} container). No credential defaults: if they are missing
 * the adapter starts "unconfigured" ({@code health} = down, operations -> 503) while the app still starts.
 *
 * @param host           FTP server name or address, e.g. {@code ftp}
 * @param port           control port (default 21)
 * @param user           the app's user
 * @param password       the app user's password
 * @param connectTimeout TCP connect timeout (default 2 s)
 * @param controlTimeout idle timeout on the control connection: command replies (default 30 s)
 * @param dataTimeout    idle timeout on the data connection: read / write (default 30 s)
 * @param healthTimeout  total timeout for {@code health()} (default 2 s, at most 2 s per the contract)
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

    /** Address and credentials for tests or code, everything else default. */
    public static FtpProperties of(String host, int port, String user, String password) {
        return new FtpProperties(host, port, user, password, null, null, null, null);
    }

    /** {@code true} if server, user and password are set. */
    public boolean isConfigured() {
        return missing().isEmpty();
    }

    /** Names of the missing variables (no values), for the log and for {@code health}. */
    String missing() {
        StringBuilder sb = new StringBuilder();
        append(sb, host, "FTP_HOST");
        append(sb, user, "FTP_USER");
        append(sb, password, "FTP_PASSWORD");
        return sb.toString();
    }

    /** Without the password (the default record toString would print it). */
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
