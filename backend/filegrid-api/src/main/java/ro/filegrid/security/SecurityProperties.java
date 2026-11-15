package ro.filegrid.security;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code filegrid.security} configuration from {@code application.yml}; secret values come only from environment
 * variables ({@code JWT_SECRET}, {@code FILEGRID_USER_*_PASSWORD}) generated into {@code .env} by {@code scripts/start.*}.
 *
 * @param jwt   token key and lifetimes
 * @param users test users (minimal login, C4): name -> role and password
 */
@ConfigurationProperties("filegrid.security")
public record SecurityProperties(Jwt jwt, Map<String, User> users) {

    public SecurityProperties {
        users = users == null ? Map.of() : Map.copyOf(users);
    }

    /**
     * @param secret          HMAC key (HS256), base64, at least 32 bytes after decoding
     * @param issuer          the {@code iss} claim
     * @param accessTokenTtl  {@code accessToken} lifetime (15 minutes)
     * @param refreshTokenTtl {@code refreshToken} lifetime (8 hours)
     */
    public record Jwt(String secret, String issuer, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    /**
     * @param role     the role
     * @param password plaintext password from the environment; empty = disabled user
     */
    public record User(Role role, String password) {
    }
}
