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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class RedisTokenBucketRateLimiterTest {

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
    void allowsUpToCapacityThenRejects() {
        KeyedRateLimiter limiter = limiter(3, 1, Duration.ofMinutes(1));

        assertTrue(limiter.tryAcquire("ip").allowed());
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertFalse(limiter.tryAcquire("ip").allowed());
    }

    @Test
    void keysHaveSeparateBuckets() {
        KeyedRateLimiter limiter = limiter(1, 1, Duration.ofMinutes(1));

        assertTrue(limiter.tryAcquire("1.1.1.1").allowed());
        assertFalse(limiter.tryAcquire("1.1.1.1").allowed());
        assertTrue(limiter.tryAcquire("2.2.2.2").allowed(), "another key must not be affected");
    }

    @Test
    void separateLimiterInstancesShareTheSameBucket() {
        // Two limiter objects stand in for two gateway servers sharing one Redis.
        KeyedRateLimiter server1 = limiter(2, 1, Duration.ofMinutes(1));
        KeyedRateLimiter server2 = limiter(2, 1, Duration.ofMinutes(1));

        assertTrue(server1.tryAcquire("ip").allowed());
        assertTrue(server2.tryAcquire("ip").allowed());
        assertFalse(server1.tryAcquire("ip").allowed(), "budget is shared, not per server");
        assertFalse(server2.tryAcquire("ip").allowed());
    }

    /**
     * The claim the Redis design rests on: however many servers send requests at the same
     * moment, one key is granted exactly its capacity. Each limiter gets its own connection,
     * like separate gateway servers would have.
     */
    @Test
    void concurrentRequestsFromSeveralServersNeverExceedCapacity() throws Exception {
        int capacity = 100;
        int servers = 3;
        int requestsPerServer = 1_000;
        // 1 token per hour: the test finishes long before a meaningful refill.
        Duration slowRefill = Duration.ofHours(1);

        List<StatefulRedisConnection<String, String>> connections = new ArrayList<>();
        List<KeyedRateLimiter> limiters = new ArrayList<>();
        for (int s = 0; s < servers; s++) {
            StatefulRedisConnection<String, String> serverConnection = client.connect();
            connections.add(serverConnection);
            limiters.add(new RedisTokenBucketRateLimiter(serverConnection, capacity, 1, slowRefill));
        }

        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < servers * requestsPerServer; i++) {
                KeyedRateLimiter limiter = limiters.get(i % servers);
                futures.add(executor.submit(() -> {
                    start.await();
                    if (limiter.tryAcquire("ip").allowed()) {
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

        assertEquals(capacity, granted.get(), "exactly capacity across all servers, no more and no fewer");
    }

    @Test
    void rejectionReportsRetryAfter() {
        // 1 token per 10s; Redis's clock keeps running, so allow a little elapsed time.
        KeyedRateLimiter limiter = limiter(1, 1, Duration.ofSeconds(10));

        RateLimitDecision allowed = limiter.tryAcquire("ip");
        assertTrue(allowed.allowed());
        assertEquals(Duration.ZERO, allowed.retryAfter());

        RateLimitDecision rejected = limiter.tryAcquire("ip");
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfter().compareTo(Duration.ofSeconds(9)) > 0
                        && rejected.retryAfter().compareTo(Duration.ofSeconds(10)) <= 0,
                "expected just under 10s but was " + rejected.retryAfter());
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        KeyedRateLimiter limiter = limiter(1, 1, Duration.ofMillis(200));

        assertTrue(limiter.tryAcquire("ip").allowed());
        assertFalse(limiter.tryAcquire("ip").allowed());

        Thread.sleep(250); // real wait: the script uses Redis's clock, which tests can't fake

        assertTrue(limiter.tryAcquire("ip").allowed());
    }

    @Test
    void multiPermitAcquire() {
        KeyedRateLimiter limiter = limiter(5, 1, Duration.ofMinutes(1));

        assertTrue(limiter.tryAcquire("ip", 3).allowed());
        assertFalse(limiter.tryAcquire("ip", 3).allowed(), "only 2 tokens left");
        assertTrue(limiter.tryAcquire("ip", 2).allowed());
    }

    @Test
    void storesStateUnderPrefixedKeyWithExpiry() {
        // capacity 10 at 1 token/s: an empty bucket refills in 10s, plus the 1s buffer.
        KeyedRateLimiter limiter = limiter(10, 1, Duration.ofSeconds(1));
        limiter.tryAcquire("1.2.3.4");

        String key = RedisTokenBucketRateLimiter.KEY_PREFIX + "1.2.3.4";
        Map<String, String> bucket = connection.sync().hgetall(key);
        assertEquals(9.0, Double.parseDouble(bucket.get("tokens")), 0.01);

        long ttlMs = connection.sync().pttl(key);
        assertTrue(ttlMs > 10_000 && ttlMs <= 11_000, "unexpected expiry: " + ttlMs + "ms");
    }

    @Test
    void recoversWhenRedisForgetsTheScript() {
        KeyedRateLimiter limiter = limiter(2, 1, Duration.ofMinutes(1));
        assertTrue(limiter.tryAcquire("ip").allowed());

        connection.sync().scriptFlush(); // what a Redis restart or failover does

        assertTrue(limiter.tryAcquire("ip").allowed());
        assertFalse(limiter.tryAcquire("ip").allowed(), "state must survive the script reload");
    }

    @Test
    void rejectsInvalidArguments() {
        KeyedRateLimiter limiter = limiter(5, 1, Duration.ofSeconds(1));
        assertThrows(NullPointerException.class, () -> limiter.tryAcquire(null));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("ip", 0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("ip", 6));
        assertThrows(IllegalArgumentException.class, () -> limiter(0, 1, Duration.ofSeconds(1)));
    }

    private static RedisTokenBucketRateLimiter limiter(long capacity, long refillTokens, Duration refillPeriod) {
        return new RedisTokenBucketRateLimiter(connection, capacity, refillTokens, refillPeriod);
    }
}
