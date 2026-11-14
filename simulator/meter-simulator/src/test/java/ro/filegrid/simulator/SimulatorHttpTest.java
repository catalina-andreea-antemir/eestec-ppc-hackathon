package ro.filegrid.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class SimulatorHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String INGEST = "/api/v1/meter-readings";
    private static final String TOKEN_PAIR = """
            {"accessToken":"old-access","refreshToken":"refresh","expiresIn":900}
            """;
    private static final String FRESH_ACCESS = """
            {"accessToken":"new-access","expiresIn":900}
            """;

    @TempDir
    Path directory;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<Duration> delays = new ArrayList<>();
    private final AtomicLong elapsed = new AtomicLong();
    private final SimulatorMetrics metrics = new SimulatorMetrics();
    private HttpServer server;
    private ExecutorService handlers;
    private HttpClient http;
    private URI base;
    private RetryPolicy retry;

    @BeforeEach
    void startMockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        retry = new RetryPolicy(Duration.ofMinutes(10), elapsed::get, Clock.systemUTC(), delay -> {
            delays.add(delay);
            elapsed.addAndGet(delay.toNanos());
        });
    }

    @AfterEach
    void stopMockServer() {
        server.stop(0);
        handlers.close();
        http.close();
    }

    @Test
    void loginRefreshAndBackoffReplayTheExactRequest() throws Exception {
        respond(LOGIN, new Reply(200, TOKEN_PAIR));
        respond(REFRESH, new Reply(200, FRESH_ACCESS));
        respond(INGEST, new Reply(401, "{}"), new Reply(429, "{}", "3"),
                new Reply(503, "{}", "1"), new Reply(201, "{}"));
        BatchDelivery delivery = delivery("storm", retry);
        delivery.login();
        var batch = new ReadingGenerator(Profile.load("normal", null)).generate(0, 0);

        assertTrue(delivery.deliver(batch));
        assertEquals(List.of(LOGIN, INGEST, REFRESH, INGEST, INGEST, INGEST),
                requests.stream().map(Request::path).toList());
        assertEquals(JSON.readTree("{\"username\":\"mock-meter\",\"password\":\"mock-only\"}"),
                JSON.readTree(requests.getFirst().body()));
        assertEquals(JSON.readTree("{\"refreshToken\":\"refresh\"}"), JSON.readTree(requests.get(2).body()));
        assertNull(requests.getFirst().authorization());
        assertNull(requests.get(2).authorization());
        List<Request> sends = ingestionRequests();
        assertEquals("Bearer old-access", sends.getFirst().authorization());
        for (int i = 0; i < sends.size(); i++) {
            Request sent = sends.get(i);
            assertEquals("POST", sent.method());
            assertEquals("application/json", sent.contentType());
            assertEquals("storm", sent.scenario());
            assertEquals(batch.idempotencyKey(), sent.key());
            assertArrayEquals(batch.payload(), sent.body());
            if (i > 0) {
                assertEquals("Bearer new-access", sent.authorization());
            }
        }
        assertEquals(List.of(Duration.ofSeconds(3), Duration.ofSeconds(2)), delays);
        assertMetrics(96, 96, 3, 0, 0);
    }

    @Test
    void refreshReusesTheOriginalRefreshTokenAndStopsAfterOneReplay() throws Exception {
        respond(LOGIN, new Reply(200, TOKEN_PAIR));
        respond(REFRESH, new Reply(200, FRESH_ACCESS), new Reply(200, FRESH_ACCESS));
        respond(INGEST, new Reply(401, "{}"), new Reply(201, "{}"),
                new Reply(401, "{}"), new Reply(401, "{}"));
        BatchDelivery delivery = delivery("normal", retry);
        ReadingGenerator generator = new ReadingGenerator(Profile.load("normal", null));
        assertTrue(delivery.deliver(generator.generate(0, 0)));
        assertFalse(delivery.deliver(generator.generate(0, 1)));
        List<Request> refreshes = requests.stream().filter(request -> request.path().equals(REFRESH)).toList();
        assertEquals(2, refreshes.size());
        assertArrayEquals(refreshes.getFirst().body(), refreshes.getLast().body());
        assertEquals(4, ingestionRequests().size());
        assertMetrics(192, 96, 2, 0, 96);
    }

    @Test
    void fallsBackToLoginWhenRefreshTokenExpires() throws Exception {
        respond(LOGIN, new Reply(200, TOKEN_PAIR), new Reply(200, """
                {"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":900}
                """));
        respond(REFRESH, new Reply(401, "{}"));
        respond(INGEST, new Reply(401, "{}"), new Reply(201, "{}"));
        BatchDelivery delivery = delivery("normal", retry);
        assertTrue(delivery.deliver(new ReadingGenerator(Profile.load("normal", null)).generate(0, 0)));
        assertEquals(List.of(LOGIN, INGEST, REFRESH, LOGIN, INGEST), requests.stream().map(Request::path).toList());
        assertEquals("Bearer new-access", ingestionRequests().getLast().authorization());
        assertMetrics(96, 96, 1, 0, 0);
    }

    @Test
    void concurrentUnauthorizedBatchesShareOneRefresh() throws Exception {
        respond(LOGIN, new Reply(200, TOKEN_PAIR));
        respond(REFRESH, new Reply(200, FRESH_ACCESS));
        CountDownLatch oldTokenRequests = new CountDownLatch(2);
        server.createContext(INGEST, exchange -> {
            try (exchange) {
                Request request = capture(exchange);
                if ("Bearer old-access".equals(request.authorization())) {
                    oldTokenRequests.countDown();
                    send(exchange, new Reply(oldTokenRequests.await(5, TimeUnit.SECONDS) ? 401 : 500, "{}"));
                } else {
                    send(exchange, new Reply(201, "{}"));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        BatchDelivery delivery = delivery("normal", retry);
        delivery.login();
        ReadingGenerator generator = new ReadingGenerator(Profile.load("normal", null));
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> delivery.deliver(generator.generate(0, 0)));
            var second = workers.submit(() -> delivery.deliver(generator.generate(1, 0)));
            assertTrue(first.get(8, TimeUnit.SECONDS));
            assertTrue(second.get(8, TimeUnit.SECONDS));
        }
        assertEquals(1, requests.stream().filter(request -> request.path().equals(REFRESH)).count());
        assertEquals(4, ingestionRequests().size());
        assertMetrics(192, 192, 2, 0, 0);
    }

    @Test
    void duplicateIsASeparateSubmission() throws Exception {
        respond(LOGIN, new Reply(200, TOKEN_PAIR));
        respond(INGEST, new Reply(201, "{}"), new Reply(200, "{\"duplicate\":true}"));
        BatchDelivery delivery = delivery("normal", retry);
        ReadingGenerator generator = new ReadingGenerator(Profile.load("normal", null));
        var original = generator.generate(0, 0);

        assertTrue(delivery.deliver(original));
        assertTrue(delivery.deliver(original));
        List<Request> sends = ingestionRequests();
        assertEquals(2, sends.size());
        assertEquals(sends.get(0).key(), sends.get(1).key());
        assertArrayEquals(sends.get(0).body(), sends.get(1).body());
        assertMetrics(192, 192, 0, 0, 0);
    }

    @Test
    void permanent409IsCountedOnceWithoutRetry() throws Exception {
        respond(LOGIN, new Reply(200, TOKEN_PAIR));
        respond(INGEST, new Reply(409, "{\"code\":\"BATCH_OVERLAP\"}", "1"));
        assertFalse(delivery("normal", retry).deliver(batch()));
        assertEquals(1, ingestionRequests().size());
        assertTrue(delays.isEmpty());
        assertMetrics(96, 0, 0, 0, 96);
    }

    @Test
    void retriesWhenTheConnectionDropsBeforeConfirmation() throws Exception {
        respond(LOGIN, new Reply(200, TOKEN_PAIR));
        respond(INGEST, Reply.DROP_CONNECTION, new Reply(200, "{\"duplicate\":true}"));
        var batch = batch();
        assertTrue(delivery("normal", retry).deliver(batch));
        assertEquals(List.of(Duration.ofSeconds(1)), delays);
        List<Request> sends = ingestionRequests();
        assertEquals(2, sends.size());
        for (Request request : sends) {
            assertEquals(batch.idempotencyKey(), request.key());
            assertArrayEquals(batch.payload(), request.body());
        }
        assertMetrics(96, 96, 1, 0, 0);
    }

    @Test
    void repeatedConnectionDropsExhaustTheTenMinuteBudget() throws Exception {
        respond(LOGIN, new Reply(200, TOKEN_PAIR));
        server.createContext(INGEST, exchange -> {
            try (exchange) {
                capture(exchange);
                // Close without a response on every POST.
            }
        });

        var batch = batch();
        assertFalse(delivery("normal", retry).deliver(batch));
        assertEquals(Duration.ofMinutes(10).toNanos(), elapsed.get());
        assertEquals(24, delays.size());
        assertEquals(Duration.ofSeconds(29), delays.getLast());
        List<Request> sends = ingestionRequests();
        assertEquals(24, sends.size());
        for (Request request : sends) {
            assertEquals(batch.idempotencyKey(), request.key());
            assertArrayEquals(batch.payload(), request.body());
        }
        assertMetrics(96, 0, 23, 0, 96);
    }

    @Test
    void unusableAuthenticationResponseEndsDeliveryAndCountsTheReadings() throws Exception {
        respond(LOGIN, new Reply(200, TOKEN_PAIR));
        respond(REFRESH, new Reply(200, "{\"accessToken\":null}"));
        respond(INGEST, new Reply(401, "{}"));
        assertFalse(delivery("normal", retry).deliver(batch()));
        assertEquals(List.of(LOGIN, INGEST, REFRESH), requests.stream().map(Request::path).toList());
        assertTrue(delays.isEmpty());
        assertMetrics(96, 0, 0, 0, 96);
    }

    @Test
    void exportsFinalMetricsAfterRecoveryConflictAndRetryDeadline() throws Exception {
        respond(LOGIN, new Reply(200, TOKEN_PAIR));
        respond(INGEST, Reply.DROP_CONNECTION, new Reply(201, "{}"),
                new Reply(409, "{\"code\":\"BATCH_OVERLAP\"}"),
                new Reply(429, "{}"), new Reply(503, "{}", "600"));
        BatchDelivery delivery = delivery("peak", retry);
        ReadingGenerator generator = new ReadingGenerator(Profile.load("peak", null));
        HttpServer exporter = metrics.serve(0);
        try {
            URI scrape = URI.create("http://127.0.0.1:" + exporter.getAddress().getPort() + "/metrics");
            assertTrue(delivery.deliver(generator.generate(0, 0)));
            assertFalse(delivery.deliver(generator.generate(0, 1)));
            assertFalse(delivery.deliver(generator.generate(0, 2)));
            assertMetrics(288, 96, 2, 0, 192);
            assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(599)), delays);
            assertEquals(5, ingestionRequests().size());
            var response = http.send(HttpRequest.newBuilder(scrape).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("""
                    # TYPE sim_readings_sent_total counter
                    sim_readings_sent_total 288
                    # TYPE sim_readings_confirmed_total counter
                    sim_readings_confirmed_total 96
                    # TYPE sim_batches_retried_total counter
                    sim_batches_retried_total 2
                    # TYPE sim_readings_pending gauge
                    sim_readings_pending 0
                    # TYPE sim_readings_abandoned_total counter
                    sim_readings_abandoned_total 192
                    """, response.body());
        } finally {
            exporter.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"normal", "peak"})
    void runsEachProfileThroughTheStandaloneEntryPoint(String name) throws Exception {
        respond(LOGIN, new Reply(200, TOKEN_PAIR));
        // Keep the profile's real meter count and injection settings, but run a shorter simulation.
        Path config = Files.writeString(directory.resolve("run.yml"), "duration: PT5S\nsendingInterval: PT1S\n");
        var confirmedKeys = ConcurrentHashMap.<String>newKeySet();
        server.createContext(INGEST, exchange -> {
            try (exchange) {
                Request request = capture(exchange);
                boolean first = confirmedKeys.add(request.key());
                send(exchange, first ? new Reply(201, "{}") : new Reply(200, "{\"duplicate\":true}"));
            }
        });

        MeterSimulator.run(Map.of("SIM_PROFILE", name, "SIM_CONFIG", config.toString(), "SIM_METRICS_PORT", "0",
                "SIM_API_BASE_URL", base.toString(), "METER_SERVICE_USERNAME", "mock-meter",
                "METER_SERVICE_PASSWORD", "mock-only"));

        Profile profile = Profile.load(name, null);
        List<Request> sends = ingestionRequests();
        assertTrue(sends.size() > profile.meters(), () -> "Requests received: " + sends.size());
        assertTrue(sends.stream().allMatch(request -> request.scenario().equals(name)));
        assertEquals(profile.meters(), sends.stream().map(Request::meterId).distinct().count());
        long distinct = sends.stream().map(Request::key).distinct().count();
        if (name.equals("peak")) {
            assertTrue(sends.size() > distinct);
            var originals = new HashMap<String, byte[]>();
            for (Request request : sends) {
                byte[] first = originals.putIfAbsent(request.key(), request.body());
                if (first != null) {
                    assertArrayEquals(first, request.body());
                }
            }
            long activeAfterFirstRound = sends.stream()
                    .filter(request -> !request.key().endsWith(profile.firstReadingAt().toString()))
                    .map(Request::meterId).distinct().count();
            assertTrue(activeAfterFirstRound > 0 && activeAfterFirstRound < profile.meters());
        } else {
            assertEquals(sends.size(), distinct);
        }
    }

    @Test
    void boundsPendingBatchesAndCleansUpWorkersOnInterrupt() throws Exception {
        respond(LOGIN, new Reply(200, TOKEN_PAIR));
        CountDownLatch inFlight = new CountDownLatch(Simulation.MAX_IN_FLIGHT);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger posts = new AtomicInteger();
        server.createContext(INGEST, exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                posts.incrementAndGet();
                inFlight.countDown();
                if (release.await(10, TimeUnit.SECONDS)) {
                    send(exchange, new Reply(201, "{}"));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        BatchDelivery delivery = delivery("peak", RetryPolicy.standard());
        delivery.login();
        Simulation simulation = new Simulation(Profile.load("peak", null), delivery);
        try (ExecutorService scheduler = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch stopped = new CountDownLatch(1);
            var running = scheduler.submit(() -> {
                try {
                    simulation.run();
                } finally {
                    stopped.countDown();
                }
                return null;
            });
            try {
                assertTrue(inFlight.await(8, TimeUnit.SECONDS));
                long pending = 96L * Simulation.MAX_IN_FLIGHT;
                assertMetrics(pending, 0, 0, pending, 0);
                assertEquals(Simulation.MAX_IN_FLIGHT, posts.get());
                running.cancel(true);
                assertTrue(stopped.await(4, TimeUnit.SECONDS));
                assertMetrics(pending, 0, 0, 0, pending);
            } finally {
                release.countDown();
                running.cancel(true);
            }
        }
    }

    private BatchDelivery delivery(String scenario, RetryPolicy policy) throws IOException {
        return new BatchDelivery(new FileGridClient(http, base, "mock-meter", "mock-only", policy),
                policy, metrics, scenario);
    }

    private ReadingGenerator.Batch batch() throws IOException {
        return new ReadingGenerator(Profile.load("normal", null)).generate(0, 0);
    }

    private void respond(String path, Reply... replies) {
        AtomicInteger index = new AtomicInteger();
        server.createContext(path, exchange -> {
            try (exchange) {
                capture(exchange);
                int attempt = index.getAndIncrement();
                Reply reply = attempt < replies.length ? replies[attempt] : new Reply(500, "{}");
                if (reply != Reply.DROP_CONNECTION) {
                    send(exchange, reply);
                }
            }
        });
    }

    private Request capture(HttpExchange exchange) throws IOException {
        var headers = exchange.getRequestHeaders();
        Request request = new Request(exchange.getRequestURI().getPath(), exchange.getRequestMethod(),
                headers.getFirst("Content-Type"), headers.getFirst("Authorization"),
                headers.getFirst("Idempotency-Key"), headers.getFirst("X-Scenario"),
                exchange.getRequestBody().readAllBytes());
        requests.add(request);
        return request;
    }

    private static void send(HttpExchange exchange, Reply reply) throws IOException {
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (reply.retryAfter() != null) {
            exchange.getResponseHeaders().set("Retry-After", reply.retryAfter());
        }
        exchange.sendResponseHeaders(reply.status(), body.length);
        exchange.getResponseBody().write(body);
    }

    private List<Request> ingestionRequests() {
        return requests.stream().filter(request -> request.path().equals(INGEST)).toList();
    }

    private void assertMetrics(long sent, long confirmed, long retried, long pending, long abandoned) {
        assertEquals(sent, metrics.sent.get(), "sent readings");
        assertEquals(confirmed, metrics.confirmed.get(), "confirmed readings");
        assertEquals(retried, metrics.retried.get(), "retried batches");
        assertEquals(pending, metrics.pending.get(), "pending readings");
        assertEquals(abandoned, metrics.abandoned.get(), "abandoned readings");
    }

    private record Request(String path, String method, String contentType, String authorization,
                           String key, String scenario, byte[] body) {
        String meterId() {
            return key.substring(0, key.indexOf('_'));
        }
    }

    private record Reply(int status, String body, String retryAfter) {
        private static final Reply DROP_CONNECTION = new Reply(0, "");

        Reply(int status, String body) {
            this(status, body, null);
        }
    }
}
