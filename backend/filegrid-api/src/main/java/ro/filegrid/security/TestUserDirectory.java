package ro.filegrid.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Test users from configuration (minimal login, C4): one per role, with passwords from
 * {@code FILEGRID_USER_*_PASSWORD}. Passwords are only kept as SHA-256 and compared in constant time; a user without
 * a password cannot log in.
 */
@Component
public class TestUserDirectory {

    private static final Logger log = LoggerFactory.getLogger(TestUserDirectory.class);
    private static final byte[] NO_USER = sha256("\u0000filegrid-no-user\u0000");

    private final Map<String, Account> accounts;

    public TestUserDirectory(SecurityProperties properties) {
        Map<String, Account> enabled = new TreeMap<>();
        properties.users().forEach((username, user) -> {
            if (user == null || user.role() == null) {
                throw new IllegalStateException("Utilizatorul de test " + username + " nu are rol");
            }
            if (user.password() == null || user.password().isBlank()) {
                log.warn("Test user {} ({}) has no password (FILEGRID_USER_*_PASSWORD) and is disabled",
                        username, user.role());
                return;
            }
            enabled.put(username, new Account(user.role(), sha256(user.password())));
        });
        if (enabled.isEmpty()) {
            log.warn("No active test users: login will always return 401");
        }
        this.accounts = Map.copyOf(enabled);
        log.info("Active test users: {}", enabled.keySet());
    }

    /** The user's role if the password is correct. */
    public Optional<Role> authenticate(String username, String password) {
        Account account = username == null ? null : accounts.get(username);
        byte[] given = sha256(password == null ? "" : password);
        // Same cost for an unknown user and a wrong password, so timing does not reveal which usernames exist.
        boolean matches = MessageDigest.isEqual(given, account == null ? NO_USER : account.passwordSha256());
        return account != null && matches ? Optional.of(account.role()) : Optional.empty();
    }

    /** Current role of an active user (on refresh, a disabled user no longer gets a token). */
    public Optional<Role> roleOf(String username) {
        Account account = username == null ? null : accounts.get(username);
        return account == null ? Optional.empty() : Optional.of(account.role());
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Account(Role role, byte[] passwordSha256) {
    }
}
