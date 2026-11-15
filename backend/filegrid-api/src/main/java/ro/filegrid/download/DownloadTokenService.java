package ro.filegrid.download;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import ro.filegrid.api.ApiException;
import ro.filegrid.integrity.Sha256;

/**
 * Signed download links (C8): {@code base64url(payload) "." base64url(HMAC-SHA256)}, with the JSON payload
 * {@code {"f": fileId, "u": user tag, "e": expiry (epoch seconds)}}. The user tag is an opaque fingerprint
 * (first 8 bytes of SHA-256 of the username), so the token carries no personal data. Any expired, tampered or
 * foreign-key-signed token yields 401.
 */
@Component
public class DownloadTokenService {

    static final int MIN_KEY_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final SecretKeySpec key;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public DownloadTokenService(DownloadProperties properties) {
        this(properties.linkSecret(), properties.linkTtl(), Clock.systemUTC());
    }

    public DownloadTokenService(String secret, Duration ttl, Clock clock) {
        this.key = key(secret);
        this.ttl = ttl;
        this.clock = clock;
    }

    /** What a valid token asserts. */
    public record Claims(UUID fileId, String userTag, Instant expiresAt) {
    }

    /** The token and its expiry. */
    public record Link(String token, Instant expiresAt) {
    }

    public Link create(UUID fileId, String username) {
        Instant expires = clock.instant().plus(ttl).truncatedTo(ChronoUnit.SECONDS);
        String json = "{\"f\":\"" + fileId + "\",\"u\":\"" + userTag(username) + "\",\"e\":" + expires.getEpochSecond()
                + "}";
        String payload = B64.encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return new Link(payload + "." + B64.encodeToString(sign(payload)), expires);
    }

    /** @throws ApiException 401 if the token is expired, invalid or tampered with */
    public Claims verify(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) {
            throw invalid();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.') || dot == token.length() - 1) {
            throw invalid();
        }
        String payload = token.substring(0, dot);
        UUID fileId;
        Instant expires;
        String tag;
        try {
            byte[] signature = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(signature, sign(payload))) {
                throw invalid();
            }
            Map<?, ?> fields = JSON.readValue(Base64.getUrlDecoder().decode(payload), Map.class);
            fileId = UUID.fromString((String) fields.get("f"));
            expires = Instant.ofEpochSecond(((Number) fields.get("e")).longValue());
            tag = (String) fields.get("u");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) { // invalid base64 / JSON / UUID: tampered or forged token
            throw invalid();
        }
        if (!clock.instant().isBefore(expires)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Link-ul de descărcare a expirat.");
        }
        return new Claims(fileId, tag, expires);
    }

    private static ApiException invalid() {
        return ApiException.unauthorized("Link de descărcare invalid.");
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String userTag(String username) {
        byte[] hash = Sha256.of((username == null ? "" : username).getBytes(StandardCharsets.UTF_8));
        return B64.encodeToString(Arrays.copyOf(hash, 8));
    }

    private static SecretKeySpec key(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Lipsește cheia link-urilor de descărcare (variabila "
                    + "DOWNLOAD_LINK_SECRET, generată de scripts/start.*)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            bytes = secret.trim().getBytes(StandardCharsets.UTF_8);
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("DOWNLOAD_LINK_SECRET are " + bytes.length + " octeți; minim "
                    + MIN_KEY_BYTES);
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
