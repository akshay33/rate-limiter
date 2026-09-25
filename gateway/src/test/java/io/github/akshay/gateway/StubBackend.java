package io.github.akshay.gateway;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal stand-in for the backend: counts requests, records the last one, and answers with a
 * configurable status, headers, body and delay.
 */
final class StubBackend implements AutoCloseable {

    static final String BODY = "{\"message\":\"stub backend\"}";

    /** What the gateway actually sent us. */
    record ReceivedRequest(String method, String path, String query, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst())
                    .findFirst().orElse(null);
        }
    }

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile ReceivedRequest lastRequest;
    private volatile int status = 200;
    private volatile String body = BODY;
    private volatile Map<String, String> extraHeaders = Map.of();
    private volatile long delayMillis;

    StubBackend() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastRequest = new ReceivedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    Map.copyOf(exchange.getRequestHeaders()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            extraHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(status, responseBody.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBody);
            } catch (IOException ignored) {
                // The gateway gave up waiting (read timeout); nothing to do.
            }
        });
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    int requestCount() {
        return requests.get();
    }

    ReceivedRequest lastRequest() {
        return lastRequest;
    }

    void resetCount() {
        requests.set(0);
    }

    /** Next responses use this status, body and extra headers. */
    void respondWith(int status, String body, Map<String, String> headers) {
        this.status = status;
        this.body = body;
        this.extraHeaders = headers;
    }

    void delayResponses(long millis) {
        this.delayMillis = millis;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
