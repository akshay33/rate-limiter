package io.github.akshay.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gateway as deployed behind nginx (X-Forwarded-For trusted), using in-memory limits so no
 * Redis is needed. Default limit: 5 requests, then 1 more every 2s. Each test uses its own client IP.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ratelimit.mode=memory",
                "ratelimit.trust-forwarded-for=true",
                "demo.show-instance=true",
                "demo.instance-id=gw-test"
        })
class GatewayTest {

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
        BACKEND.resetCount();
    }

    @Test
    void allowedRequestIsForwardedToBackend() {
        HttpResponse<String> response = gateway.get("/api/hello", "10.0.0.1");

        assertEquals(200, response.statusCode());
        assertEquals(StubBackend.BODY, response.body());
        assertEquals(1, BACKEND.requestCount());
        assertEquals("gw-test", response.headers().firstValue(RateLimitFilter.SERVED_BY_HEADER).orElse(null));
    }

    @Test
    void overTheLimitGets429WithRetryAfterAndNeverReachesBackend() {
        for (int i = 0; i < 5; i++) {
            assertEquals(200, gateway.get("/api/hello", "10.0.0.2").statusCode(), "request " + (i + 1));
        }

        HttpResponse<String> rejected = gateway.get("/api/hello", "10.0.0.2");

        assertEquals(429, rejected.statusCode());
        long retryAfter = Long.parseLong(rejected.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retryAfter >= 1 && retryAfter <= 2, "Retry-After was " + retryAfter);
        assertEquals("{\"error\":\"Too Many Requests\"}", rejected.body());
        assertEquals(5, BACKEND.requestCount(), "the rejected request must not be forwarded");
    }

    @Test
    void differentClientsHaveSeparateLimits() {
        for (int i = 0; i < 5; i++) {
            gateway.get("/api/hello", "10.0.0.3");
        }
        assertEquals(429, gateway.get("/api/hello", "10.0.0.3").statusCode());

        assertEquals(200, gateway.get("/api/hello", "10.0.0.4").statusCode(), "another client is unaffected");
    }

    @Test
    void usesTheLastForwardedForEntrySoClientsCantForgeTheirIp() {
        // A client can put anything on the left; the last entry is what our proxy saw.
        for (int i = 0; i < 5; i++) {
            assertEquals(200, gateway.get("/api/hello", "6.6.6." + i + ", 10.0.0.5").statusCode());
        }
        assertEquals(429, gateway.get("/api/hello", "7.7.7.7, 10.0.0.5").statusCode());
    }

    @Test
    void demoPageIsServedAndNotLimited() {
        for (int i = 0; i < 5; i++) {
            gateway.get("/api/hello", "10.0.0.7");
        }
        assertEquals(429, gateway.get("/api/hello", "10.0.0.7").statusCode());

        HttpResponse<String> page = gateway.get("/", "10.0.0.7");
        assertEquals(200, page.statusCode(), "the page must load even when the client is rate limited");
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(page.body().contains("Send request"));
    }

    @Test
    void nonApiPathsAreNotLimited() {
        for (int i = 0; i < 5; i++) {
            gateway.get("/api/hello", "10.0.0.6");
        }
        assertEquals(429, gateway.get("/api/hello", "10.0.0.6").statusCode());

        for (int i = 0; i < 10; i++) {
            assertEquals(200, gateway.get("/actuator/health", "10.0.0.6").statusCode());
        }
    }
}
