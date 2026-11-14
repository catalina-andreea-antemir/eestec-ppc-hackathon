package ro.filegrid.simulator;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

final class SimulatorMetrics {
    final AtomicLong sent = new AtomicLong();
    final AtomicLong confirmed = new AtomicLong();
    final AtomicLong retried = new AtomicLong();
    final AtomicLong pending = new AtomicLong();
    final AtomicLong abandoned = new AtomicLong();

    HttpServer serve(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 16);
        server.createContext("/metrics", exchange -> {
            try (exchange) {
                if (!exchange.getRequestURI().getPath().equals("/metrics")) {
                    exchange.sendResponseHeaders(404, -1);
                } else if (!exchange.getRequestMethod().equals("GET")) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    exchange.sendResponseHeaders(405, -1);
                } else {
                    byte[] body = prometheus().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            }
        });
        server.start();
        return server;
    }

    String prometheus() {
        return String.format(Locale.ROOT, """
                # TYPE sim_readings_sent_total counter
                sim_readings_sent_total %d
                # TYPE sim_readings_confirmed_total counter
                sim_readings_confirmed_total %d
                # TYPE sim_batches_retried_total counter
                sim_batches_retried_total %d
                # TYPE sim_readings_pending gauge
                sim_readings_pending %d
                # TYPE sim_readings_abandoned_total counter
                sim_readings_abandoned_total %d
                """, sent.get(), confirmed.get(), retried.get(), pending.get(), abandoned.get());
    }
}
