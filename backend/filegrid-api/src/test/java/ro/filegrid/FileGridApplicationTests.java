package ro.filegrid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Smoke: the app starts without a database (default profile) and exposes health/prometheus. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FileGridApplicationTests {

    @Autowired
    TestRestTemplate rest;

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        TestSecrets.register(registry);
    }

    @Test
    void healthIsUp() {
        ResponseEntity<String> r = rest.getForEntity("/actuator/health", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void prometheusIsExposed() {
        ResponseEntity<String> r = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("jvm_memory_used_bytes");
    }

    @Test
    void otherPathsRequireAuthentication() {
        ResponseEntity<String> r = rest.getForEntity("/api/v1/files", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void actuatorEnvIsNotExposed() {
        ResponseEntity<String> r = rest.getForEntity("/actuator/env", String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isFalse();
    }
}
