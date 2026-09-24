package io.github.akshay.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Default setting (not behind nginx): a forged X-Forwarded-For must not give a client a fresh limit. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ratelimit.mode=memory")
class GatewayUntrustedForwardedForTest {

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
    void forgedForwardedForIsIgnored() {
        GatewayClient gateway = new GatewayClient(port);

        // Every request claims a different IP, but they all come from the same connection.
        for (int i = 0; i < 5; i++) {
            assertEquals(200, gateway.get("/api/hello", "1.2.3." + i).statusCode());
        }
        assertEquals(429, gateway.get("/api/hello", "9.9.9.9").statusCode());
    }
}
