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
 * Minimal login (contract {@code POST /auth/login}, {@code POST /auth/refresh}; C4). Both are public; an
 * {@code Authorization} header sent here is ignored (see {@link SecurityConfig}).
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

    /** 200 with {@code accessToken} (15 minutes), {@code refreshToken} (8 hours) and {@code expiresIn}; otherwise 401. */
    @PostMapping("/login")
    public TokenPair login(@Valid @RequestBody LoginRequest request) {
        Role role = users.authenticate(request.username(), request.password()).orElseThrow(() -> {
            log.warn("Login failed for user {}", printable(request.username()));
            return ApiException.unauthorized("Utilizator sau parolă greșită.");
        });
        log.info("Login succeeded: {} ({})", request.username(), role);
        return new TokenPair(tokens.issueAccessToken(request.username(), role),
                tokens.issueRefreshToken(request.username()), tokens.accessTokenTtlSeconds());
    }

    /** 200 with a fresh {@code accessToken}, no password; invalid or expired refreshToken, or disabled user: 401. */
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

    /** Name as logged: no control characters, at most 100 characters. */
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
