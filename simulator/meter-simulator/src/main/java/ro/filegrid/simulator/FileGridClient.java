package ro.filegrid.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.filegrid.simulator.ReadingGenerator.Batch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscribers;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

final class FileGridClient {
    private static final int MAX_AUTH_RESPONSE_BYTES = 16_384;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI base;
    private final byte[] credentials;
    private final RetryPolicy retry;
    private final ReentrantLock authLock = new ReentrantLock();
    private Tokens tokens;

    FileGridClient(HttpClient http, URI base, String username, String password, RetryPolicy retry) throws IOException {
        this.http = http;
        this.base = base;
        this.credentials = JSON.writeValueAsBytes(Map.of("username", username, "password", password));
        this.retry = retry;
    }

    Tokens authenticate(Tokens rejected, long deadline) throws IOException, InterruptedException {
        if (!authLock.tryLock(retry.remaining(deadline).toNanos(), TimeUnit.NANOSECONDS)) {
            throw new IOException("Delivery deadline reached while waiting for authentication");
        }
        try {
            if (tokens == null) {
                tokens = login(deadline);
            } else if (tokens == rejected) {
                HttpResponse<byte[]> response = post("auth/refresh",
                        JSON.writeValueAsBytes(Map.of("refreshToken", tokens.refreshToken())),
                        deadline, Map.of(), true);
                if (response.statusCode() == 401) {
                    // Refresh tokens expire too. Acquire a new pair with the configured account.
                    tokens = login(deadline);
                } else {
                    tokens = new Tokens(token(authBody(response), "accessToken"), tokens.refreshToken());
                }
            }
            return tokens;
        } finally {
            authLock.unlock();
        }
    }

    HttpResponse<byte[]> send(Batch batch, String scenario, Tokens auth, long deadline, Runnable onSend)
            throws IOException, InterruptedException {
        return post("meter-readings", batch.payload(), deadline, Map.of(
                "Authorization", "Bearer " + auth.accessToken(),
                "Idempotency-Key", batch.idempotencyKey(),
                "X-Scenario", scenario), false, onSend);
    }

    private Tokens login(long deadline) throws IOException, InterruptedException {
        JsonNode body = authBody(post("auth/login", credentials, deadline, Map.of(), true));
        return new Tokens(token(body, "accessToken"), token(body, "refreshToken"));
    }

    private static JsonNode authBody(HttpResponse<byte[]> response) throws IOException {
        if (response.statusCode() != 200) {
            throw new AuthFailure(response.statusCode(), response.headers().firstValue("Retry-After"));
        }
        try {
            JsonNode parsed = JSON.readTree(response.body());
            if (parsed != null && parsed.isObject()) {
                return parsed;
            }
        } catch (IOException invalidJson) {
            // Jackson parse messages may contain response content, including credentials or tokens.
        }
        throw new IOException("Invalid authentication response");
    }

    private static String token(JsonNode body, String field) throws IOException {
        String value = body.path(field).asText("");
        if (!body.path(field).isTextual() || !value.matches("[A-Za-z0-9._~-]+")) {
            throw new IOException("Invalid authentication field: " + field);
        }
        return value;
    }

    private HttpResponse<byte[]> post(String path, byte[] body, long deadline, Map<String, String> headers,
                                      boolean readBody) throws IOException, InterruptedException {
        return post(path, body, deadline, headers, readBody, () -> {});
    }

    private HttpResponse<byte[]> post(String path, byte[] body, long deadline, Map<String, String> headers,
                                      boolean readBody, Runnable onSend) throws IOException, InterruptedException {
        Duration remaining = retry.remaining(deadline);
        if (remaining.isZero()) {
            throw new IOException("Delivery deadline reached");
        }
        Duration timeout = remaining.compareTo(REQUEST_TIMEOUT) < 0 ? remaining : REQUEST_TIMEOUT;
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        onSend.run();
        var exchange = http.sendAsync(builder.build(), info ->
                readBody && info.statusCode() == 200 ? limitedBody() : BodySubscribers.replacing(null));
        try {
            // Also bound the time spent reading the body after the headers arrive.
            return exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            exchange.cancel(true);
            throw interrupted;
        } catch (TimeoutException timeoutElapsed) {
            exchange.cancel(true);
            throw new TransientTransportFailure("Timed out waiting for " + path, timeoutElapsed);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            String message = "HTTP exchange failed for " + path;
            if (cause instanceof IOException && !(cause instanceof SSLHandshakeException)
                    && !(cause instanceof SSLPeerUnverifiedException) && !(cause instanceof ProtocolException)) {
                throw new TransientTransportFailure(message, cause);
            }
            throw new IOException(message, cause);
        }
    }

    private static HttpResponse.BodySubscriber<byte[]> limitedBody() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        return BodySubscribers.mapping(BodySubscribers.ofByteArrayConsumer(chunk -> chunk.ifPresent(bytes -> {
            if (bytes.length > MAX_AUTH_RESPONSE_BYTES - buffer.size()) {
                throw new IllegalStateException("Authentication response is too large");
            }
            buffer.writeBytes(bytes);
        })), ignored -> buffer.toByteArray());
    }

    record Tokens(String accessToken, String refreshToken) {}

    static final class TransientTransportFailure extends IOException {
        TransientTransportFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class AuthFailure extends IOException {
        final int status;
        final Optional<String> retryAfter;

        AuthFailure(int status, Optional<String> retryAfter) {
            super("Authentication returned HTTP " + status);
            this.status = status;
            this.retryAfter = retryAfter;
        }
    }
}
