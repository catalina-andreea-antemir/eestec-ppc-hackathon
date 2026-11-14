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
 * Utilizatorii de test din configurație (login minim, planul v4 secțiunea 4 și C4): câte unul per rol, cu parolele
 * din {@code FILEGRID_USER_*_PASSWORD}. Parolele se țin doar ca SHA-256 și se compară în timp constant; un utilizator
 * fără parolă nu se poate autentifica.
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
                log.warn("Utilizatorul de test {} ({}) nu are parolă (FILEGRID_USER_*_PASSWORD) și este dezactivat",
                        username, user.role());
                return;
            }
            enabled.put(username, new Account(user.role(), sha256(user.password())));
        });
        if (enabled.isEmpty()) {
            log.warn("Niciun utilizator de test activ: login-ul va răspunde mereu 401");
        }
        this.accounts = Map.copyOf(enabled);
        log.info("Utilizatori de test activi: {}", enabled.keySet());
    }

    /** Rolul utilizatorului dacă parola este corectă. */
    public Optional<Role> authenticate(String username, String password) {
        Account account = username == null ? null : accounts.get(username);
        byte[] given = sha256(password == null ? "" : password);
        // Același cost pentru utilizator necunoscut și parolă greșită.
        boolean matches = MessageDigest.isEqual(given, account == null ? NO_USER : account.passwordSha256());
        return account != null && matches ? Optional.of(account.role()) : Optional.empty();
    }

    /** Rolul curent al unui utilizator activ (la refresh: un utilizator dezactivat nu mai primește token). */
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
