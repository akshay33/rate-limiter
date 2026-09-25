package io.github.akshay.ratelimiter.redis;

import io.github.akshay.ratelimiter.KeyedRateLimiter;
import io.github.akshay.ratelimiter.RateLimitDecision;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Heavier and edge-case checks of the Redis limiter (step 10 of the v2 plan). */
@Testcontainers
class RedisEdgeCasesTest {

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

    @Test
    void manyKeysUnderConcurrentLoadStayIsolated() throws Exception {
        int capacity = 20;
        int keys = 25;
        int servers = 3;
        List<StatefulRedisConnection<String, String>> connections = new ArrayList<>();
        List<KeyedRateLimiter> limiters = new ArrayList<>();
        for (int s = 0; s < servers; s++) {
            StatefulRedisConnection<String, String> c = client.connect();
            connections.add(c);
            limiters.add(new RedisTokenBucketRateLimiter(c, capacity, 1, Duration.ofHours(1)));
        }
        AtomicInteger[] granted = new AtomicInteger[keys];
        for (int k = 0; k < keys; k++) {
            granted[k] = new AtomicInteger();
        }

        try {
            // 4x each key's capacity, spread over all keys and all "servers" at once.
            runConcurrently(keys * capacity * 4, i -> {
                int key = i % keys;
                if (limiters.get(i % servers).tryAcquire("key-" + key).allowed()) {
                    granted[key].incrementAndGet();
                }
            });
        } finally {
            connections.forEach(StatefulRedisConnection::close);
        }

        for (int k = 0; k < keys; k++) {
            assertEquals(capacity, granted[k].get(), "key-" + k);
        }
    }

    @Test
    void everyRefillRoundGrantsOneFullBucketUnderConcurrentLoad() throws Exception {
        int capacity = 5;
        KeyedRateLimiter limiter = new RedisTokenBucketRateLimiter(connection, capacity, capacity, Duration.ofMillis(500));

        for (int round = 0; round < 4; round++) {
            Thread.sleep(600); // refills completely between rounds
            AtomicInteger granted = new AtomicInteger();
            runConcurrently(40, i -> {
                if (limiter.tryAcquire("ip").allowed()) {
                    granted.incrementAndGet();
                }
            });
            // The burst takes a few ms, during which a fraction of a token can refill.
            assertTrue(granted.get() == capacity || granted.get() == capacity + 1,
                    "round " + round + ": granted " + granted.get());
        }
    }

    @Test
    void expiredBucketStartsFullExactlyOnceUnderConcurrentLoad() throws Exception {
        int capacity = 3;
        // Refills in 100 ms; the key then expires 1 s later (full refill + buffer).
        KeyedRateLimiter limiter = new RedisTokenBucketRateLimiter(connection, capacity, capacity, Duration.ofMillis(100));
        for (int i = 0; i < capacity; i++) {
            limiter.tryAcquire("ip");
        }
        String key = RedisTokenBucketRateLimiter.KEY_PREFIX + "ip";
        assertEquals(1, connection.sync().exists(key));

        Thread.sleep(1_300);
        assertEquals(0, connection.sync().exists(key), "idle bucket should have expired");

        // Many requests race to recreate the bucket: only one full bucket's worth may be granted.
        AtomicInteger granted = new AtomicInteger();
        runConcurrently(50, i -> {
            if (limiter.tryAcquire("ip").allowed()) {
                granted.incrementAndGet();
            }
        });
        assertEquals(capacity, granted.get());
    }

    @Test
    void verySlowRateReportsLongRetryAfterAndExpiry() {
        Duration year = Duration.ofDays(365);
        KeyedRateLimiter limiter = new RedisTokenBucketRateLimiter(connection, 1, 1, year);

        assertTrue(limiter.tryAcquire("ip").allowed());
        RateLimitDecision rejected = limiter.tryAcquire("ip");

        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfter().compareTo(year.minusMinutes(1)) > 0
                && rejected.retryAfter().compareTo(year) <= 0, "retry after " + rejected.retryAfter());
        long ttlMs = connection.sync().pttl(RedisTokenBucketRateLimiter.KEY_PREFIX + "ip");
        assertTrue(ttlMs > year.toMillis() && ttlMs <= year.toMillis() + 1_000, "expiry " + ttlMs + "ms");
    }

    @Test
    void veryFastRateHandlesLargeNumbers() throws InterruptedException {
        int million = 1_000_000;
        KeyedRateLimiter limiter = new RedisTokenBucketRateLimiter(connection, million, million, Duration.ofSeconds(1));

        assertTrue(limiter.tryAcquire("ip", million).allowed(), "a full bucket covers a million permits at once");
        RateLimitDecision rejected = limiter.tryAcquire("ip", million);
        assertFalse(rejected.allowed());
        assertTrue(!rejected.retryAfter().isZero() && rejected.retryAfter().compareTo(Duration.ofSeconds(1)) <= 0,
                "retry after " + rejected.retryAfter());

        Thread.sleep(1_100);
        assertTrue(limiter.tryAcquire("ip", million).allowed(), "refilled a million tokens in a second");
    }

    @Test
    void sustainedRateMatchesConfiguredRate() {
        // 1 token every 50 ms, so fractional refills accumulate constantly.
        KeyedRateLimiter limiter = new RedisTokenBucketRateLimiter(connection, 1, 1, Duration.ofMillis(50));
        long start = System.nanoTime();
        int granted = 0;
        while (System.nanoTime() - start < Duration.ofSeconds(2).toNanos()) {
            if (limiter.tryAcquire("ip").allowed()) {
                granted++;
            }
        }
        // 1 up front + 2 s / 50 ms = 41
        assertTrue(granted >= 39 && granted <= 42, "granted " + granted + " in 2 s, expected about 41");
    }

    private static void runConcurrently(int tasks, IntConsumer body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(tasks);
            for (int i = 0; i < tasks; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    body.accept(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }
    }
}
