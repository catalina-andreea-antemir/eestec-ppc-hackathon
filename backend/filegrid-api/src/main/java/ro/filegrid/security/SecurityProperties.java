package ro.filegrid.security;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurația {@code filegrid.security} din {@code application.yml}; valorile secrete vin doar din variabile de mediu
 * ({@code JWT_SECRET}, {@code FILEGRID_USER_*_PASSWORD}), generate de {@code scripts/start.*} în {@code .env}.
 *
 * @param jwt   cheia și duratele token-urilor
 * @param users utilizatorii de test (login minim, C4): nume → rol și parolă
 */
@ConfigurationProperties("filegrid.security")
public record SecurityProperties(Jwt jwt, Map<String, User> users) {

    public SecurityProperties {
        users = users == null ? Map.of() : Map.copyOf(users);
    }

    /**
     * @param secret          cheia HMAC (HS256), în base64, cel puțin 32 de octeți după decodare
     * @param issuer          claim-ul {@code iss}
     * @param accessTokenTtl  valabilitatea {@code accessToken} (15 minute)
     * @param refreshTokenTtl valabilitatea {@code refreshToken} (8 ore)
     */
    public record Jwt(String secret, String issuer, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    /**
     * @param role     rolul
     * @param password parola în clar, din mediu; goală = utilizator dezactivat
     */
    public record User(Role role, String password) {
    }
}
