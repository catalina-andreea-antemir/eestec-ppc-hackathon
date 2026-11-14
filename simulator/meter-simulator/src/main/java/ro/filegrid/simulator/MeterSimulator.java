package ro.filegrid.simulator;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class MeterSimulator {
    private static final System.Logger LOG = System.getLogger(MeterSimulator.class.getName());

    private MeterSimulator() {}

    public static void main(String[] args) {
        try {
            run(System.getenv());
        } catch (InterruptedException shutdown) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "Simulator stopped: {0}", failure.getMessage());
            System.exit(1);
        }
    }

    static void run(Map<String, String> env) throws IOException, InterruptedException {
        String profileName = env.getOrDefault("SIM_PROFILE", "normal");
        String config = env.get("SIM_CONFIG");
        Profile profile = Profile.load(profileName, config == null ? null : Path.of(config));
        URI base = apiBase(env.getOrDefault("SIM_API_BASE_URL", "http://localhost:8080/api/v1"));
        String username = required(env, "METER_SERVICE_USERNAME");
        String password = required(env, "METER_SERVICE_PASSWORD");
        int metricsPort = Integer.parseInt(env.getOrDefault("SIM_METRICS_PORT", "9400"));

        Thread main = Thread.currentThread();
        CountDownLatch stopped = new CountDownLatch(1);
        Thread shutdownHook = Thread.ofPlatform().unstarted(() -> {
            main.interrupt();
            try {
                stopped.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        SimulatorMetrics metrics = new SimulatorMetrics();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpServer metricsServer = metrics.serve(metricsPort);
            try {
                RetryPolicy retry = RetryPolicy.standard();
                FileGridClient client = new FileGridClient(http, base, username, password, retry);
                BatchDelivery delivery = new BatchDelivery(client, retry, metrics, profile.scenario());
                LOG.log(System.Logger.Level.INFO, "Profile {0}, scenario {1}, {2} meters, metrics on port {3}",
                        profileName, profile.scenario(), profile.meters(), metricsServer.getAddress().getPort());
                delivery.login();
                new Simulation(profile, delivery).run();
            } finally {
                metricsServer.stop(0);
                System.out.print(metrics.prometheus());
            }
        } finally {
            stopped.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException shutdownInProgress) {
                // The JVM is already running the hook.
            }
        }
    }

    private static URI apiBase(String value) {
        URI uri = URI.create(value.endsWith("/") ? value : value + "/");
        if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null
                || !uri.getPath().endsWith("/api/v1/")
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("SIM_API_BASE_URL must be an HTTP(S) URL ending in /api/v1");
        }
        return uri;
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing environment variable " + name);
        }
        return value;
    }
}
