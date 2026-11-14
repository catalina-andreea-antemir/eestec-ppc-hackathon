package ro.filegrid.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Emite și verifică token-urile (contract: {@code /auth/login}, {@code /auth/refresh}). Ambele sunt JWT HS256 semnate
 * cu {@code JWT_SECRET}, deci orice instanță {@code api} le acceptă (stateless, fără revocare):
 * <ul>
 *   <li>{@code accessToken}: 15 minute; claim-uri {@code iss}, {@code sub}, {@code iat}, {@code exp}, {@code jti},
 *       {@code typ = "access"}, {@code role} (șir, ca în contract) și {@code roles} (listă cu același rol, citită de
 *       interfața Angular).</li>
 *   <li>{@code refreshToken}: 8 ore; claim-uri {@code iss}, {@code sub}, {@code iat}, {@code exp}, {@code jti},
 *       {@code typ = "refresh"}, fără rol (rolul se ia din configurație la reînnoire).</li>
 * </ul>
 * Câmpul {@code typ} împiedică folosirea unui refreshToken ca accessToken și invers.
 */
@Component
public class TokenService {

    static final String TYPE_CLAIM = "typ";
    static final String ACCESS = "access";
    static final String REFRESH = "refresh";
    /** Toleranța de ceas între instanțe. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final int MIN_KEY_BYTES = 32;

    private final JwtEncoder encoder;
    private final JwtDecoder accessDecoder;
    private final JwtDecoder refreshDecoder;
    private final String issuer;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final Clock clock;

    @Autowired
    public TokenService(SecurityProperties properties) {
        this(properties.jwt(), Clock.systemUTC());
    }

    public TokenService(SecurityProperties.Jwt jwt, Clock clock) {
        Objects.requireNonNull(jwt, "filegrid.security.jwt");
        SecretKey key = secretKey(jwt.secret());
        this.issuer = Objects.requireNonNull(jwt.issuer(), "filegrid.security.jwt.issuer");
        this.accessTtl = Objects.requireNonNull(jwt.accessTokenTtl(), "filegrid.security.jwt.access-token-ttl");
        this.refreshTtl = Objects.requireNonNull(jwt.refreshTokenTtl(), "filegrid.security.jwt.refresh-token-ttl");
        this.clock = clock;
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.accessDecoder = decoder(key, ACCESS);
        this.refreshDecoder = decoder(key, REFRESH);
    }

    /** Decodorul folosit de resource server pentru {@code Authorization: Bearer}. */
    public JwtDecoder accessTokenDecoder() {
        return accessDecoder;
    }

    /** Valabilitatea accessToken-ului, în secunde ({@code expiresIn}). */
    public long accessTokenTtlSeconds() {
        return accessTtl.toSeconds();
    }

    public String issueAccessToken(String username, Role role) {
        Instant now = clock.instant();
        JwtClaimsSet claims = baseClaims(username, now, accessTtl, ACCESS)
                .claim("role", role.name())
                .claim("roles", List.of(role.name()))
                .build();
        return encode(claims);
    }

    public String issueRefreshToken(String username) {
        return encode(baseClaims(username, clock.instant(), refreshTtl, REFRESH).build());
    }

    /**
     * Verifică un refreshToken (semnătură, emitent, expirare, {@code typ = "refresh"}).
     *
     * @return utilizatorul ({@code sub})
     * @throws JwtException dacă token-ul nu este valid
     */
    public String verifyRefreshToken(String token) {
        return refreshDecoder.decode(token).getSubject();
    }

    private JwtClaimsSet.Builder baseClaims(String username, Instant now, Duration ttl, String type) {
        return JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim(TYPE_CLAIM, type);
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private JwtDecoder decoder(SecretKey key, String expectedType) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> type = jwt -> expectedType.equals(jwt.getClaimAsString(TYPE_CLAIM))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Tip de token greșit", null));
        OAuth2TokenValidator<Jwt> subject = jwt -> jwt.getSubject() != null && !jwt.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Lipsește sub", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(issuer), type, subject));
        return decoder;
    }

    private static SecretKey secretKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Lipsește cheia JWT (variabila JWT_SECRET, generată de scripts/start.*)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            try {
                bytes = Base64.getUrlDecoder().decode(secret.trim());
            } catch (IllegalArgumentException e2) {
                throw new IllegalStateException("JWT_SECRET trebuie să fie în base64");
            }
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("JWT_SECRET are " + bytes.length + " octeți; HS256 cere minim "
                    + MIN_KEY_BYTES);
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
