package io.github.akshay.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The algorithm setting takes effect: 5 per 10 s window, so the wait is ~10 s, not the token bucket's ~2 s. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ratelimit.mode=memory",
                "ratelimit.algorithm=sliding-window-log",
                "ratelimit.limit=5",
                "ratelimit.window=10s",
                "ratelimit.trust-forwarded-for=true"
        })
class GatewaySlidingWindowLogTest {

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

    @Test
    void usesTheConfiguredAlgorithm() {
        GatewayClient gateway = new GatewayClient(port);
        for (int i = 0; i < 5; i++) {
            assertEquals(200, gateway.get("/api/hello", "10.6.0.1").statusCode());
        }

        HttpResponse<String> rejected = gateway.get("/api/hello", "10.6.0.1");

        assertEquals(429, rejected.statusCode());
        long retryAfter = Long.parseLong(rejected.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retryAfter >= 9 && retryAfter <= 10, "window of 10 s, got Retry-After " + retryAfter);
    }
}
