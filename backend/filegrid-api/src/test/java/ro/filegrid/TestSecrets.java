package ro.filegrid;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Secrete aleatorii pentru testele Spring (niciun secret în repo): cheia JWT și parolele utilizatorilor de test,
 * generate o dată per rulare.
 */
public final class TestSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static final String JWT_SECRET = Base64.getEncoder().encodeToString(bytes(48));
    public static final String DISPECER_PASSWORD = HexFormat.of().formatHex(bytes(12));
    public static final String ADMIN_PASSWORD = HexFormat.of().formatHex(bytes(12));

    private TestSecrets() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("filegrid.security.jwt.secret", () -> JWT_SECRET);
        registry.add("filegrid.security.users.dispecer.password", () -> DISPECER_PASSWORD);
        registry.add("filegrid.security.users.admin.password", () -> ADMIN_PASSWORD);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
