package io.github.akshay.gateway;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The algorithm setting also applies in Redis mode: counts are kept in a sliding-window-counter key. */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ratelimit.algorithm=sliding-window-counter",
                "ratelimit.limit=5",
                "ratelimit.window=10s",
                "ratelimit.trust-forwarded-for=true"
        })
class GatewaySlidingWindowCounterRedisTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final StubBackend BACKEND = new StubBackend();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("backend.url", BACKEND::url);
        registry.add("ratelimit.redis.url", GatewaySlidingWindowCounterRedisTest::redisUrl);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.close();
    }

    @Value("${local.server.port}")
    private int port;

    @Test
    void enforcesTheLimitThroughRedis() {
        GatewayClient gateway = new GatewayClient(port);
        for (int i = 0; i < 5; i++) {
            assertEquals(200, gateway.get("/api/hello", "10.7.0.1").statusCode());
        }
        assertEquals(429, gateway.get("/api/hello", "10.7.0.1").statusCode());

        RedisClient client = RedisClient.create(redisUrl());
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            assertEquals(1, connection.sync().exists("ratelimit:sw-counter:10.7.0.1"));
        } finally {
            client.shutdown();
        }
    }

    private static String redisUrl() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }
}
