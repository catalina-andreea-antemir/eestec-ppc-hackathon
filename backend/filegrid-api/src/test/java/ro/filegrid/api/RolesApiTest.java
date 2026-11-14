package ro.filegrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import ro.filegrid.TestSecrets;
import ro.filegrid.security.Role;
import ro.filegrid.security.TokenService;

/** Matricea 4.4 la nivel HTTP: 401 fără token, 403 {@code FORBIDDEN} cu rol greșit (fără baza de date). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RolesApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    TestRestTemplate rest;
    @Autowired
    TokenService tokens;

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        TestSecrets.register(registry);
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, Role role, Object body,
                                                     MediaType type) {
        HttpHeaders headers = new HttpHeaders();
        if (role != null) {
            headers.setBearerAuth(tokens.issueAccessToken("u-" + role, role));
        }
        if (type != null) {
            headers.setContentType(type);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> upload(Role role, String category) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("category", category);
        form.add("file", new ByteArrayResource(new byte[] {1, 2, 3}) {
            @Override
            public String getFilename() {
                return "x.bin";
            }
        });
        return call(HttpMethod.POST, "/api/v1/files", role, form, MediaType.MULTIPART_FORM_DATA);
    }

    private void forbidden(ResponseEntity<Map<String, Object>> r, String what) {
        assertThat(r.getStatusCode().value()).as(what).isEqualTo(403);
        assertThat(r.getBody()).as(what).containsEntry("code", "FORBIDDEN");
    }

    private void allowed(ResponseEntity<Map<String, Object>> r, String what) {
        assertThat(r.getStatusCode().value()).as(what).isNotIn(401, 403);
    }

    @Test
    void noTokenGives401OnEveryProtectedEndpoint() {
        for (String path : new String[] {"/api/v1/files", "/api/v1/stats/x", "/api/v1/admin/x"}) {
            assertThat(call(HttpMethod.GET, path, null, null, null).getStatusCode().value()).as(path).isEqualTo(401);
        }
        assertThat(call(HttpMethod.POST, "/api/v1/meter-readings", null, "{}", MediaType.APPLICATION_JSON)
                .getStatusCode().value()).isEqualTo(401);
        assertThat(upload(null, "CONNECTION_DOC").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void urlLevelRules() {
        forbidden(call(HttpMethod.GET, "/api/v1/files", Role.METER_SERVICE, null, null), "METER_SERVICE GET /files");
        forbidden(call(HttpMethod.POST, "/api/v1/uploads", Role.METER_SERVICE, "{}", MediaType.APPLICATION_JSON),
                "METER_SERVICE POST /uploads");
        forbidden(call(HttpMethod.POST, "/api/v1/uploads", Role.CONNECTIONS, "{}", MediaType.APPLICATION_JSON),
                "CONNECTIONS POST /uploads");
        forbidden(call(HttpMethod.POST, "/api/v1/uploads", Role.DISPATCH, "{}", MediaType.APPLICATION_JSON),
                "DISPATCH POST /uploads");
        for (Role role : new Role[] {Role.FIELD, Role.CONNECTIONS, Role.METER_SERVICE}) {
            forbidden(call(HttpMethod.GET, "/api/v1/stats/summary", role, null, null), role + " /stats");
        }
        for (Role role : new Role[] {Role.FIELD, Role.CONNECTIONS, Role.METER_SERVICE, Role.DISPATCH}) {
            forbidden(call(HttpMethod.GET, "/api/v1/admin/health", role, null, null), role + " /admin");
        }
        for (Role role : new Role[] {Role.DISPATCH, Role.FIELD, Role.CONNECTIONS}) {
            forbidden(call(HttpMethod.POST, "/api/v1/meter-readings", role, "{}", MediaType.APPLICATION_JSON),
                    role + " POST /meter-readings");
        }
        allowed(call(HttpMethod.GET, "/api/v1/stats/summary", Role.DISPATCH, null, null), "DISPATCH /stats");
        allowed(call(HttpMethod.GET, "/api/v1/stats/summary", Role.ADMIN, null, null), "ADMIN /stats");
        allowed(call(HttpMethod.GET, "/api/v1/admin/health", Role.ADMIN, null, null), "ADMIN /admin");
        allowed(call(HttpMethod.POST, "/api/v1/meter-readings", Role.METER_SERVICE, "{}", MediaType.APPLICATION_JSON),
                "METER_SERVICE POST /meter-readings");
    }

    @Test
    void uploadChecksTheRoleAgainstTheCategory() {
        forbidden(upload(Role.DISPATCH, "OUTAGE_RECORD"), "DISPATCH upload OUTAGE_RECORD");
        forbidden(upload(Role.DISPATCH, "CONNECTION_DOC"), "DISPATCH upload CONNECTION_DOC");
        forbidden(upload(Role.FIELD, "CONNECTION_DOC"), "FIELD upload CONNECTION_DOC");
        forbidden(upload(Role.CONNECTIONS, "OUTAGE_RECORD"), "CONNECTIONS upload OUTAGE_RECORD");
        forbidden(upload(Role.METER_SERVICE, "OUTAGE_RECORD"), "METER_SERVICE upload OUTAGE_RECORD");
    }
}
