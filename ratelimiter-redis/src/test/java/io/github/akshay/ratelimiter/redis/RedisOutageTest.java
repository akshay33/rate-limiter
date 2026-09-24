package io.github.akshay.ratelimiter.redis;

import io.github.akshay.ratelimiter.FallbackKeyedRateLimiter;
import io.github.akshay.ratelimiter.InMemoryKeyedRateLimiter;
import io.github.akshay.ratelimiter.KeyedRateLimiter;
import io.github.akshay.ratelimiter.RateLimiterUnavailableException;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What happens when Redis goes away. Each test gets its own container because tests stop or freeze it. */
@Testcontainers
class RedisOutageTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(100);
    /** Generous bound: the point is "fails fast", not "waits Lettuce's default 60s". */
    private static final long FAST_MS = 1_000;

    @Container
    private final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisTokenBucketRateLimiter redisLimiter;

    @BeforeEach
    void connect() {
        RedisURI uri = RedisURI.builder()
                .withHost(redis.getHost())
                .withPort(redis.getMappedPort(6379))
                .withTimeout(COMMAND_TIMEOUT)
                .build();
        client = RedisClient.create(uri);
        // Fail immediately while disconnected instead of queueing commands until the timeout.
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        connection = client.connect();
        redisLimiter = new RedisTokenBucketRateLimiter(connection, 5, 1, Duration.ofMinutes(1));
    }

    @AfterEach
    void disconnect() {
        connection.close();
        client.shutdown(Duration.ZERO, Duration.ofSeconds(1));
    }

    @Test
    void stoppedRedisFailsFastWithUnavailableException() {
        assertTrue(redisLimiter.tryAcquire("ip").allowed());

        redis.stop();

        long start = System.nanoTime();
        assertThrows(RateLimiterUnavailableException.class, () -> redisLimiter.tryAcquire("ip"));
        assertFast(start);
    }

    @Test
    void unresponsiveRedisTimesOutInsteadOfHanging() {
        assertTrue(redisLimiter.tryAcquire("ip").allowed());

        // A paused container keeps the connection open but never replies: the "slow Redis" case.
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            assertThrows(RateLimiterUnavailableException.class, () -> redisLimiter.tryAcquire("ip"));
            assertFast(start);
        } finally {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }
    }

    @Test
    void fallbackKeepsLimitingDuringOutage() {
        KeyedRateLimiter inMemory = new InMemoryKeyedRateLimiter(2, 1, Duration.ofMinutes(1));
        FallbackKeyedRateLimiter limiter = new FallbackKeyedRateLimiter(redisLimiter, inMemory);
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertFalse(limiter.isUsingFallback());

        redis.stop();

        long start = System.nanoTime();
        // Still limited (by the in-memory fallback's capacity of 2), not wide open and not erroring.
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertFalse(limiter.tryAcquire("ip").allowed());
        assertTrue(limiter.isUsingFallback());
        assertFast(start);
    }

    private static void assertFast(long startNanos) {
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        assertTrue(elapsedMs < FAST_MS, "took " + elapsedMs + "ms; should fail fast, not hang");
    }
}
