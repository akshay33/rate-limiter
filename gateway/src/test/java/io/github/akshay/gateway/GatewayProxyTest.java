package io.github.akshay.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forwarding to the backend: requests and responses pass through intact; backend failures map to 502/504. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ratelimit.mode=memory",
                "ratelimit.trust-forwarded-for=true",
                "backend.read-timeout=300ms"
        })
class GatewayProxyTest {

    private static final StubBackend BACKEND = new StubBackend();

    @DynamicPropertySource
    static void backendUrl(DynamicPropertyRegistry registry) {
        registry.add("backend.url", BACKEND::url);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.close();
    }

    @Value("${local.server.port}")
    private int port;

    private GatewayClient gateway;

    @BeforeEach
    void setUp() {
        gateway = new GatewayClient(port);
        BACKEND.respondWith(200, StubBackend.BODY, Map.of());
        BACKEND.delayResponses(0);
    }

    @Test
    @Order(1)
    void requestPassesThroughIntact() {
        HttpResponse<String> response = gateway.send("POST", "/api/orders?sort=desc&page=2", "{\"item\":42}",
                Map.of("Content-Type", "application/json", "X-Custom", "hello", "X-Forwarded-For", "10.3.0.1"));

        assertEquals(200, response.statusCode());
        StubBackend.ReceivedRequest received = BACKEND.lastRequest();
        assertEquals("POST", received.method());
        assertEquals("/api/orders", received.path());
        assertEquals("sort=desc&page=2", received.query());
        assertEquals("{\"item\":42}", received.body());
        assertEquals("hello", received.header("X-Custom"));
        assertEquals("application/json", received.header("Content-Type"));
    }

    @Test
    @Order(2)
    void responsePassesThroughIntact() {
        BACKEND.respondWith(201, "{\"id\":7}", Map.of("X-Backend", "yes", "Location", "/api/orders/7"));

        HttpResponse<String> response = gateway.send("POST", "/api/orders", "{}", Map.of("X-Forwarded-For", "10.3.0.2"));

        assertEquals(201, response.statusCode());
        assertEquals("{\"id\":7}", response.body());
        assertEquals("yes", response.headers().firstValue("X-Backend").orElse(null));
        assertEquals("/api/orders/7", response.headers().firstValue("Location").orElse(null));
    }

    @Test
    @Order(3)
    void backendErrorStatusIsPassedThroughNotReplaced() {
        BACKEND.respondWith(500, "{\"error\":\"boom\"}", Map.of());

        HttpResponse<String> response = gateway.get("/api/hello", "10.3.0.3");

        assertEquals(500, response.statusCode());
        assertEquals("{\"error\":\"boom\"}", response.body());
    }

    @Test
    @Order(4)
    void slowBackendGets504WithoutHanging() {
        BACKEND.delayResponses(2_000); // read timeout is 300 ms

        long start = System.nanoTime();
        HttpResponse<String> response = gateway.get("/api/hello", "10.3.0.4");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(504, response.statusCode());
        assertEquals("{\"error\":\"Gateway Timeout\"}", response.body());
        assertTrue(elapsedMs < 1_500, "should give up after the read timeout, took " + elapsedMs + "ms");
    }

    @Test
    @Order(5)
    void unreachableBackendGets502() {
        BACKEND.close(); // nothing listens on the backend port any more

        HttpResponse<String> response = gateway.get("/api/hello", "10.3.0.5");

        assertEquals(502, response.statusCode());
        assertEquals("{\"error\":\"Bad Gateway\"}", response.body());
    }
}
