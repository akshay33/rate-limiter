package io.github.akshay.gateway;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal stand-in for the backend: answers every request and counts how many arrived. */
final class StubBackend implements AutoCloseable {

    static final String BODY = "{\"message\":\"stub backend\"}";

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    StubBackend() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
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

    void resetCount() {
        requests.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
