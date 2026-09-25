package io.github.akshay.ratelimiter.redis;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.github.akshay.ratelimiter.FallbackKeyedRateLimiter;
import io.github.akshay.ratelimiter.InMemoryKeyedRateLimiter;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis restarts while the limiter is in use. Redis runs without persistence (like a pure cache),
 * so a restart wipes the buckets and the script cache.
 */
class RedisRestartTest {

    private GenericContainer<?> redis;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private FallbackKeyedRateLimiter limiter;

    @BeforeEach
    void start() throws IOException {
        // A fixed host port, so the address stays the same across a container restart.
        int hostPort = freePort();
        redis = new GenericContainer<>("redis:7-alpine")
                .withCommand("redis-server", "--save", "")
                .withExposedPorts(6379)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(hostPort), new ExposedPort(6379))));
        redis.start();

        client = RedisClient.create(RedisURI.builder()
                .withHost(redis.getHost()).withPort(hostPort).withTimeout(Duration.ofMillis(100)).build());
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        connection = client.connect();
        limiter = new FallbackKeyedRateLimiter(
                new RedisTokenBucketRateLimiter(connection, 3, 1, Duration.ofHours(1)),
                new InMemoryKeyedRateLimiter(3, 1, Duration.ofHours(1)));
    }

    @AfterEach
    void stop() {
        connection.close();
        client.shutdown(Duration.ZERO, Duration.ofSeconds(1));
        redis.stop();
    }

    @Test
    void restartIsSurvivedAndRedisTakesOverAgain() throws Exception {
        // Use up the shared bucket in Redis.
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("ip").allowed());
        }
        assertFalse(limiter.tryAcquire("ip").allowed());

        redis.getDockerClient().restartContainerCmd(redis.getContainerId()).withTimeout(1).exec();

        // Requests never fail during or after the restart; wait for the limiter to be back on Redis.
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        boolean backOnRedis = false;
        while (System.nanoTime() < deadline) {
            limiter.tryAcquire("probe"); // throws if anything leaks through; the fallback must absorb outages
            if (!limiter.isUsingFallback()) {
                backOnRedis = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(backOnRedis, "limiter should switch back to Redis after the restart");

        // Buckets were wiped with the restart, so the client starts fresh. The Lua script also had to be
        // reloaded (NOSCRIPT), and limiting through Redis must still work.
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("ip").allowed(), "fresh bucket after restart, request " + (i + 1));
        }
        assertFalse(limiter.tryAcquire("ip").allowed());
        assertFalse(limiter.isUsingFallback(), "those decisions came from Redis, not the fallback");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
