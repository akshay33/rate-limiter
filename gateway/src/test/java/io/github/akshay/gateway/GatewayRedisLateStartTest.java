package io.github.akshay.gateway;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redis isn't running when the gateway starts, then comes up: the gateway switches to it on its own. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ratelimit.trust-forwarded-for=true",
                "ratelimit.redis.reconnect-interval=300ms"
        })
class GatewayRedisLateStartTest {

    private static final int REDIS_PORT = freePort();
    /** Docker's host address; not localhost when the tests themselves run in a container. */
    private static final String REDIS_HOST = DockerClientFactory.instance().dockerHostIpAddress();
    private static final StubBackend BACKEND = new StubBackend();
    private static GenericContainer<?> redis;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("backend.url", BACKEND::url);
        registry.add("ratelimit.redis.url", () -> "redis://" + REDIS_HOST + ":" + REDIS_PORT);
    }

    @AfterAll
    static void cleanUp() {
        BACKEND.close();
        if (redis != null) {
            redis.stop();
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ReconnectingRedisRateLimiter redisLimiter;

    @Test
    void switchesToRedisOnceItComesUp() throws Exception {
        assertFalse(redisLimiter.isConnected(), "Redis isn't running yet");
        GatewayClient gateway = new GatewayClient(port);
        assertEquals(200, gateway.get("/api/hello", "10.4.0.1").statusCode(), "works on the fallback meanwhile");

        redis = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))));
        redis.start();

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!redisLimiter.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(redisLimiter.isConnected(), "background reconnect should connect once Redis is up");

        assertEquals(200, gateway.get("/api/hello", "10.4.0.2").statusCode());
        RedisClient client = RedisClient.create("redis://" + REDIS_HOST + ":" + REDIS_PORT);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            assertEquals(1, connection.sync().exists("ratelimit:10.4.0.2"), "new requests are counted in Redis");
        } finally {
            client.shutdown();
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
