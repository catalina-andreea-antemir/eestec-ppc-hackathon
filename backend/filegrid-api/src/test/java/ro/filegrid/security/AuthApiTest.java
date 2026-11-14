package ro.filegrid.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ro.filegrid.TestSecrets;

/** Login minim (C4) cap-coadă, prin HTTP: contractul {@code /auth/login}, {@code /auth/refresh}, 401 și X-Trace-Id. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthApiTest {

    private static final String PROTECTED = "/api/v1/files";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper json;

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        TestSecrets.register(registry);
    }

    @Test
    void loginReturnsTokensWithRoleAndRolesClaims() throws Exception {
        ResponseEntity<Map<String, Object>> r = login("dispecer", TestSecrets.DISPECER_PASSWORD);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getFirst("X-Trace-Id")).matches("[0-9a-f]{32}");
        assertThat(r.getBody()).containsOnlyKeys("accessToken", "refreshToken", "expiresIn");
        assertThat(r.getBody().get("expiresIn")).isEqualTo(900);

        Map<String, Object> access = payload((String) r.getBody().get("accessToken"));
        assertThat(access).containsEntry("sub", "dispecer").containsEntry("role", "DISPATCH")
                .containsEntry("roles", List.of("DISPATCH")).containsEntry("typ", "access")
                .containsEntry("iss", "filegrid");
        assertThat(((Number) access.get("exp")).longValue() - ((Number) access.get("iat")).longValue())
                .isEqualTo(900);

        Map<String, Object> refresh = payload((String) r.getBody().get("refreshToken"));
        assertThat(refresh).containsEntry("sub", "dispecer").containsEntry("typ", "refresh")
                .doesNotContainKeys("role", "roles");
        assertThat(((Number) refresh.get("exp")).longValue() - ((Number) refresh.get("iat")).longValue())
                .isEqualTo(8 * 3600);
    }

    @Test
    void wrongPasswordUnknownUserAndDisabledUserGet401() {
        for (String[] credentials : new String[][] {{"dispecer", "gresit"}, {"nimeni", "x"},
                {"teren", "orice"}, {"admin", TestSecrets.DISPECER_PASSWORD}}) {
            ResponseEntity<Map<String, Object>> r = login(credentials[0], credentials[1]);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(r.getBody()).containsEntry("status", 401).containsEntry("code", "UNAUTHORIZED")
                    .containsEntry("traceId", r.getHeaders().getFirst("X-Trace-Id"));
            assertThat(r.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        }
    }

    @Test
    void invalidLoginBodyGets400() {
        ResponseEntity<Map<String, Object>> r = post("/api/v1/auth/login", Map.of("username", "dispecer"), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("code", "VALIDATION_ERROR");

        ResponseEntity<Map<String, Object>> notJson = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{nu e json", jsonHeaders(null)), new ParameterizedTypeReference());
        assertThat(notJson.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refreshGivesANewAccessTokenWithoutPassword() throws Exception {
        String refreshToken = (String) login("admin", TestSecrets.ADMIN_PASSWORD).getBody().get("refreshToken");

        ResponseEntity<Map<String, Object>> r = post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken),
                null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsOnlyKeys("accessToken", "expiresIn").containsEntry("expiresIn", 900);
        Map<String, Object> access = payload((String) r.getBody().get("accessToken"));
        assertThat(access).containsEntry("sub", "admin").containsEntry("role", "ADMIN")
                .containsEntry("roles", List.of("ADMIN"));
        assertThat(get(PROTECTED, (String) r.getBody().get("accessToken")).getStatusCode())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshRejectsAccessTokensAndGarbage() {
        String accessToken = (String) login("admin", TestSecrets.ADMIN_PASSWORD).getBody().get("accessToken");

        for (String token : new String[] {accessToken, "abc.def.ghi", "x"}) {
            ResponseEntity<Map<String, Object>> r = post("/api/v1/auth/refresh", Map.of("refreshToken", token), null);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(r.getBody()).containsEntry("code", "UNAUTHORIZED");
        }
    }

    @Test
    void protectedEndpointNeedsAValidAccessToken() {
        ResponseEntity<Map<String, Object>> none = get(PROTECTED, null);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(none.getBody()).containsEntry("code", "UNAUTHORIZED");
        assertThat(none.getHeaders().getFirst("X-Trace-Id")).isNotBlank();
        assertThat(none.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");

        Map<String, Object> tokens = login("dispecer", TestSecrets.DISPECER_PASSWORD).getBody();
        ResponseEntity<Map<String, Object>> ok = get(PROTECTED, (String) tokens.get("accessToken"));
        assertThat(ok.getStatusCode()).as("autentificat; endpoint-ul încă nu există").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ok.getBody()).containsEntry("code", "NOT_FOUND");

        assertThat(get(PROTECTED, (String) tokens.get("refreshToken")).getStatusCode())
                .as("refreshToken nu ține loc de accessToken").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(PROTECTED, tamper((String) tokens.get("accessToken"))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredOrForeignTokensGet401() {
        SecurityProperties.Jwt jwt = new SecurityProperties.Jwt(TestSecrets.JWT_SECRET, "filegrid",
                Duration.ofMinutes(15), Duration.ofHours(8));
        Clock anHourAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String expired = new TokenService(jwt, anHourAgo).issueAccessToken("dispecer", Role.DISPATCH);
        assertThat(get(PROTECTED, expired).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        SecurityProperties.Jwt otherKey = new SecurityProperties.Jwt(
                Base64.getEncoder().encodeToString(new byte[48]), "filegrid", Duration.ofMinutes(15),
                Duration.ofHours(8));
        String foreign = new TokenService(otherKey, Clock.systemUTC()).issueAccessToken("admin", Role.ADMIN);
        assertThat(get(PROTECTED, foreign).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String expiredRefresh = new TokenService(new SecurityProperties.Jwt(TestSecrets.JWT_SECRET, "filegrid",
                Duration.ofMinutes(15), Duration.ofMinutes(30)), anHourAgo).issueRefreshToken("dispecer");
        assertThat(post("/api/v1/auth/refresh", Map.of("refreshToken", expiredRefresh), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void healthIsPublicEvenWithABadToken() {
        ResponseEntity<Map<String, Object>> r = get("/api/v1/health", "token-expirat");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEqualTo(Map.of("status", "UP"));
        assertThat(r.getHeaders().getFirst("X-Trace-Id")).matches("[0-9a-f]{32}");
    }

    @Test
    void loginIgnoresAStaleAuthorizationHeader() {
        ResponseEntity<Map<String, Object>> r = post("/api/v1/auth/login",
                Map.of("username", "dispecer", "password", TestSecrets.DISPECER_PASSWORD), "token-expirat");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- utilitare -------------------------------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> login(String username, String password) {
        return post("/api/v1/auth/login", Map.of("username", username, "password", password), null);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body, String bearer) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, jsonHeaders(bearer)),
                new ParameterizedTypeReference());
    }

    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(jsonHeaders(bearer)),
                new ParameterizedTypeReference());
    }

    private static HttpHeaders jsonHeaders(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return headers;
    }

    private Map<String, Object> payload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
        return json.readValue(new String(decoded, StandardCharsets.UTF_8), new TypeReference<>() { });
    }

    private static String tamper(String jwt) {
        char last = jwt.charAt(jwt.length() - 2);
        return jwt.substring(0, jwt.length() - 2) + (last == 'A' ? 'B' : 'A') + jwt.charAt(jwt.length() - 1);
    }

    private static final class ParameterizedTypeReference
            extends org.springframework.core.ParameterizedTypeReference<Map<String, Object>> {
    }
}
