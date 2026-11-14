package ro.filegrid.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ro.filegrid.api.ApiException;

/**
 * Login minim (contract: {@code POST /auth/login}, {@code POST /auth/refresh}; planul v4 secțiunea 4, C4). Ambele
 * sunt publice; un header {@code Authorization} trimis aici se ignoră (vezi {@link SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final TestUserDirectory users;
    private final TokenService tokens;

    public AuthController(TestUserDirectory users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** 200 cu {@code accessToken} (15 minute), {@code refreshToken} (8 ore) și {@code expiresIn}; altfel 401. */
    @PostMapping("/login")
    public TokenPair login(@Valid @RequestBody LoginRequest request) {
        Role role = users.authenticate(request.username(), request.password()).orElseThrow(() -> {
            log.warn("Autentificare eșuată pentru utilizatorul {}", printable(request.username()));
            return ApiException.unauthorized("Utilizator sau parolă greșită.");
        });
        log.info("Autentificare reușită: {} ({})", request.username(), role);
        return new TokenPair(tokens.issueAccessToken(request.username(), role),
                tokens.issueRefreshToken(request.username()), tokens.accessTokenTtlSeconds());
    }

    /** 200 cu un {@code accessToken} nou, fără parolă; refreshToken invalid, expirat sau utilizator dezactivat: 401. */
    @PostMapping("/refresh")
    public AccessToken refresh(@Valid @RequestBody RefreshRequest request) {
        String username;
        try {
            username = tokens.verifyRefreshToken(request.refreshToken());
        } catch (JwtException e) {
            throw ApiException.unauthorized("Token de reîmprospătare invalid sau expirat; autentifică-te din nou.");
        }
        Role role = users.roleOf(username).orElseThrow(() ->
                ApiException.unauthorized("Utilizatorul nu mai este activ; autentifică-te din nou."));
        return new AccessToken(tokens.issueAccessToken(username, role), tokens.accessTokenTtlSeconds());
    }

    /** Numele din loguri: fără caractere de control, maxim 100 de caractere. */
    private static String printable(String value) {
        String clean = value == null ? "" : value.replaceAll("\\p{Cntrl}", "?");
        return clean.length() > 100 ? clean.substring(0, 100) + "…" : clean;
    }

    public record LoginRequest(@NotBlank @Size(max = 100) String username,
                               @NotBlank @Size(max = 200) String password) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 4096) String refreshToken) {
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresIn) {
    }

    public record AccessToken(String accessToken, long expiresIn) {
    }
}
