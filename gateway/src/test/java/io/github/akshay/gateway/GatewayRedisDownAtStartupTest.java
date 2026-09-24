package io.github.akshay.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Redis mode with no Redis running: the gateway still starts and still limits (in-memory fallback). */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ratelimit.trust-forwarded-for=true",
                "ratelimit.redis.url=redis://localhost:1" // nothing listens on port 1
        })
class GatewayRedisDownAtStartupTest {

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

    @Autowired
    private ReconnectingRedisRateLimiter redisLimiter;

    @Test
    void startsAndLimitsWithFallbackWhenRedisIsDown() {
        assertFalse(redisLimiter.isConnected());
        GatewayClient gateway = new GatewayClient(port);

        for (int i = 0; i < 5; i++) {
            assertEquals(200, gateway.get("/api/hello", "10.2.0.1").statusCode());
        }
        assertEquals(429, gateway.get("/api/hello", "10.2.0.1").statusCode(), "still limited, not wide open");
    }
}
