package io.github.akshay.ratelimiter.redis;

import io.github.akshay.ratelimiter.KeyedRateLimiter;
import io.github.akshay.ratelimiter.RateLimitConfig;
import io.github.akshay.ratelimiter.RateLimitDecision;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every algorithm, built through {@link RedisRateLimiters}, against a real Redis. */
@Testcontainers
class RedisRateLimitersTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        connection = client.connect();
    }

    @AfterAll
    static void disconnect() {
        connection.close();
        client.shutdown();
    }

    @BeforeEach
    void clearRedis() {
        connection.sync().flushall();
    }

    /** Limit 5, with no meaningful refill during a test. */
    static Stream<RateLimitConfig> everyAlgorithm() {
        return Stream.of(
                RateLimitConfig.tokenBucket(5, 1, Duration.ofHours(1)),
                RateLimitConfig.slidingWindowLog(5, Duration.ofHours(1)),
                RateLimitConfig.slidingWindowCounter(5, Duration.ofHours(1)));
    }

    @ParameterizedTest
    @MethodSource("everyAlgorithm")
    void allowsTheLimitThenRejectsWithARetryTime(RateLimitConfig config) {
        KeyedRateLimiter limiter = RedisRateLimiters.create(connection, config);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("ip").allowed(), "request " + (i + 1));
        }
        RateLimitDecision rejected = limiter.tryAcquire("ip");
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfter().compareTo(Duration.ZERO) > 0
                && rejected.retryAfter().compareTo(Duration.ofHours(2)) <= 0, "retry after " + rejected.retryAfter());
    }

    @ParameterizedTest
    @MethodSource("everyAlgorithm")
    void keysAreIndependent(RateLimitConfig config) {
        KeyedRateLimiter limiter = RedisRateLimiters.create(connection, config);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("a");
        }
        assertFalse(limiter.tryAcquire("a").allowed());
        assertTrue(limiter.tryAcquire("b").allowed());
    }

    @ParameterizedTest
    @MethodSource("everyAlgorithm")
    void severalServersGetExactlyTheLimitUnderConcurrentLoad(RateLimitConfig config) throws Exception {
        RateLimitConfig big = switch (config) {
            case RateLimitConfig.TokenBucket c -> RateLimitConfig.tokenBucket(100, 1, Duration.ofHours(1));
            case RateLimitConfig.SlidingWindowLog c -> RateLimitConfig.slidingWindowLog(100, Duration.ofHours(1));
            case RateLimitConfig.SlidingWindowCounter c -> RateLimitConfig.slidingWindowCounter(100, Duration.ofHours(1));
        };
        List<StatefulRedisConnection<String, String>> connections = new ArrayList<>();
        List<KeyedRateLimiter> servers = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            StatefulRedisConnection<String, String> c = client.connect();
            connections.add(c);
            servers.add(RedisRateLimiters.create(c, big));
        }
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 1_500; i++) {
                KeyedRateLimiter server = servers.get(i % 3);
                futures.add(executor.submit(() -> {
                    start.await();
                    if (server.tryAcquire("ip").allowed()) {
                        granted.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            connections.forEach(StatefulRedisConnection::close);
        }
        assertEquals(100, granted.get());
    }

    @ParameterizedTest
    @MethodSource("everyAlgorithm")
    void stateExpiresOnItsOwn(RateLimitConfig config) {
        RedisRateLimiters.create(connection, config).tryAcquire("ip");

        List<String> keys = connection.sync().keys("ratelimit:*");
        assertEquals(1, keys.size(), "one key per client: " + keys);
        assertTrue(connection.sync().pttl(keys.getFirst()) > 0, "key must have an expiry");
    }

    @Test
    void algorithmsUseSeparateKeysSoSwitchingIsSafe() {
        for (RateLimitConfig config : everyAlgorithm().toList()) {
            assertTrue(RedisRateLimiters.create(connection, config).tryAcquire("ip").allowed());
        }
        assertEquals(3, connection.sync().keys("ratelimit:*").size(), "no key shared between algorithms");
    }

    @Test
    void slidingWindowLogFreesRoomAsTheWindowSlides() throws InterruptedException {
        KeyedRateLimiter limiter = RedisRateLimiters.create(connection, RateLimitConfig.slidingWindowLog(2, Duration.ofMillis(500)));
        assertTrue(limiter.tryAcquire("ip").allowed());
        Thread.sleep(300);
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertFalse(limiter.tryAcquire("ip").allowed());

        Thread.sleep(250); // the first request (at 0 ms) has left; the second (at 300 ms) hasn't
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertFalse(limiter.tryAcquire("ip").allowed());
    }

    @Test
    void slidingWindowCounterRecoversAfterTheWindowPasses() throws InterruptedException {
        KeyedRateLimiter limiter = RedisRateLimiters.create(connection, RateLimitConfig.slidingWindowCounter(3, Duration.ofMillis(300)));
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("ip").allowed());
        }
        assertFalse(limiter.tryAcquire("ip").allowed());

        Thread.sleep(700); // over two windows: the old counts no longer matter
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("ip").allowed());
        }
    }
}
