package io.github.akshay.ratelimiter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.akshay.ratelimiter.TokenBucketRateLimiterTest.assertDurationNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryKeyedRateLimiterTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    @Test
    void keysHaveSeparateBuckets() {
        AtomicLong clock = new AtomicLong(0);
        KeyedRateLimiter limiter = limiter(2, clock, InMemoryKeyedRateLimiter.DEFAULT_SWEEP_INTERVAL);

        assertTrue(limiter.tryAcquire("1.1.1.1").allowed());
        assertTrue(limiter.tryAcquire("1.1.1.1").allowed());
        assertFalse(limiter.tryAcquire("1.1.1.1").allowed());

        assertTrue(limiter.tryAcquire("2.2.2.2").allowed(), "another key must not be affected");
    }

    @Test
    void rejectionReportsRetryAfter() {
        AtomicLong clock = new AtomicLong(0);
        KeyedRateLimiter limiter = limiter(1, clock, InMemoryKeyedRateLimiter.DEFAULT_SWEEP_INTERVAL);

        RateLimitDecision allowed = limiter.tryAcquire("ip");
        assertTrue(allowed.allowed());
        assertEquals(Duration.ZERO, allowed.retryAfter());

        clock.addAndGet(Duration.ofMillis(400).toNanos());
        RateLimitDecision rejected = limiter.tryAcquire("ip");
        assertFalse(rejected.allowed());
        assertDurationNear(Duration.ofMillis(600), rejected.retryAfter());
    }

    @Test
    void rejectsInvalidArguments() {
        KeyedRateLimiter limiter = new InMemoryKeyedRateLimiter(5, 1, ONE_SECOND);
        assertThrows(NullPointerException.class, () -> limiter.tryAcquire(null));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("ip", 0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("ip", 6));
    }

    @Test
    void sweepRemovesFullyRefilledBuckets() {
        AtomicLong clock = new AtomicLong(0);
        InMemoryKeyedRateLimiter limiter = limiter(2, clock, Duration.ofSeconds(30));

        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        assertEquals(2, limiter.size());

        // Past the sweep interval; both buckets have long since refilled.
        clock.addAndGet(Duration.ofSeconds(31).toNanos());
        limiter.tryAcquire("c");

        assertEquals(1, limiter.size(), "only the bucket for 'c' should remain");
    }

    @Test
    void sweepKeepsBucketsThatAreStillRefilling() {
        AtomicLong clock = new AtomicLong(0);
        // Slow refill: 1 token per minute, so buckets stay partially empty past the sweep.
        InMemoryKeyedRateLimiter limiter = new InMemoryKeyedRateLimiter(
                2, 1, Duration.ofMinutes(1), clock::get, Duration.ofSeconds(30));

        limiter.tryAcquire("a");
        clock.addAndGet(Duration.ofSeconds(31).toNanos());
        limiter.tryAcquire("b");

        assertEquals(2, limiter.size(), "'a' is not full yet, so it must be kept");
        // 'a' kept its state: 1 token left (+ half a token of refill), so only one more acquire fits.
        assertTrue(limiter.tryAcquire("a").allowed());
        assertFalse(limiter.tryAcquire("a").allowed());
    }

    @Test
    void sweepDoesNotRunBeforeInterval() {
        AtomicLong clock = new AtomicLong(0);
        InMemoryKeyedRateLimiter limiter = limiter(2, clock, Duration.ofSeconds(30));

        limiter.tryAcquire("a");
        clock.addAndGet(Duration.ofSeconds(10).toNanos());
        limiter.tryAcquire("b");

        assertEquals(2, limiter.size());
    }

    @Test
    void concurrentAcquiresAreExactPerKey() throws Exception {
        int capacity = 100;
        int keys = 10;
        int requestsPerKey = 500;
        AtomicLong clock = new AtomicLong(0); // frozen: no refill
        KeyedRateLimiter limiter = limiter(capacity, clock, InMemoryKeyedRateLimiter.DEFAULT_SWEEP_INTERVAL);

        AtomicInteger[] granted = new AtomicInteger[keys];
        for (int k = 0; k < keys; k++) {
            granted[k] = new AtomicInteger();
        }

        runConcurrently(keys * requestsPerKey, i -> {
            int k = i % keys;
            if (limiter.tryAcquire("key-" + k).allowed()) {
                granted[k].incrementAndGet();
            }
        });

        for (int k = 0; k < keys; k++) {
            assertEquals(capacity, granted[k].get(), "key-" + k);
        }
    }

    /**
     * Regression test for the sweep/acquire race (known-issues/in-memory-keyed-sweep-race.md).
     * Each round starts with a full bucket and a sweep on every call, which is exactly when
     * the sweeper can remove a bucket that a request is using. Every round must grant exactly
     * {@code capacity}; an orphaned bucket would let extra requests through.
     */
    @Test
    void sweepingDuringConcurrentAcquiresNeverOverGrants() throws Exception {
        int capacity = 5;
        int threadsPerRound = 20;
        AtomicLong clock = new AtomicLong(0);
        InMemoryKeyedRateLimiter limiter = new InMemoryKeyedRateLimiter(
                capacity, capacity, ONE_SECOND, clock::get, Duration.ZERO);

        for (int round = 0; round < 300; round++) {
            clock.addAndGet(ONE_SECOND.toNanos()); // refill completely, so the bucket is full and sweepable
            AtomicInteger granted = new AtomicInteger();

            runConcurrently(threadsPerRound, i -> {
                if (limiter.tryAcquire("ip").allowed()) {
                    granted.incrementAndGet();
                }
            });

            assertEquals(capacity, granted.get(), "round " + round);
        }
    }

    /**
     * Refill, sweeps and acquires all overlapping: the clock moves forward while many threads
     * acquire, with a sweep on every call. The bucket starts drained, so every refilled token must
     * be granted exactly once. If a sweep ever replaced a partly used bucket with a fresh full one,
     * more would be granted; if a token were lost, fewer.
     *
     * <p>Capacity exceeds the total refill, so the bucket never fills up and discards tokens, even on a slow CI machine.
     */
    @Test
    void tokensAreConservedWhileTheClockMovesUnderLoad() throws Exception {
        int capacity = 100;
        int refillSteps = 20; // each step refills exactly 1 token
        AtomicLong clock = new AtomicLong(0);
        InMemoryKeyedRateLimiter limiter = new InMemoryKeyedRateLimiter(
                capacity, 1, Duration.ofMillis(100), clock::get, Duration.ZERO);

        // Start empty: a full bucket would (correctly) discard refills beyond capacity.
        for (int i = 0; i < capacity; i++) {
            assertTrue(limiter.tryAcquire("ip").allowed());
        }

        AtomicInteger granted = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean clockDone = new java.util.concurrent.atomic.AtomicBoolean();
        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        // Platform threads: spinning virtual threads can starve the clock task on a 2-CPU machine and hang the test.
        try (ExecutorService executor = Executors.newFixedThreadPool(workers + 1)) {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    for (int step = 0; step < refillSteps; step++) {
                        Thread.sleep(1); // give workers time to consume each refill
                        clock.addAndGet(Duration.ofMillis(100).toNanos());
                    }
                } finally {
                    clockDone.set(true);
                }
                return null;
            }));
            for (int w = 0; w < workers; w++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    while (!clockDone.get()) {
                        if (limiter.tryAcquire("ip").allowed()) {
                            granted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }
        // Collect anything refilled after the workers stopped, so every token is accounted for.
        // Bounded, so a limiter that allows everything fails the assertion instead of looping forever.
        for (int i = 0; i < capacity + refillSteps && limiter.tryAcquire("ip").allowed(); i++) {
            granted.incrementAndGet();
        }

        assertEquals(refillSteps, granted.get(), "every refilled token granted exactly once");
    }

    @Test
    void retryAfterStaysWithinBoundsForMultiPermitRequestsUnderLoad() throws Exception {
        int capacity = 5;
        int permits = 3;
        AtomicLong clock = new AtomicLong(0);
        KeyedRateLimiter limiter = limiter(capacity, clock, InMemoryKeyedRateLimiter.DEFAULT_SWEEP_INTERVAL);
        // 1 token per second, so refilling 3 permits from empty takes at most 3 s.
        Duration maxWait = Duration.ofSeconds(permits);
        java.util.Queue<Duration> waits = new java.util.concurrent.ConcurrentLinkedQueue<>();

        runConcurrently(200, i -> {
            RateLimitDecision decision = limiter.tryAcquire("ip", permits);
            if (!decision.allowed()) {
                waits.add(decision.retryAfter());
            }
        });

        assertEquals(199, waits.size(), "only one 3-permit request fits in a bucket of 5");
        for (Duration wait : waits) {
            assertTrue(!wait.isZero() && !wait.isNegative() && wait.compareTo(maxWait) <= 0,
                    "retry-after out of bounds: " + wait);
        }
    }

    private static InMemoryKeyedRateLimiter limiter(long capacity, AtomicLong clock, Duration sweepInterval) {
        return new InMemoryKeyedRateLimiter(capacity, 1, ONE_SECOND, clock::get, sweepInterval);
    }

    /** Runs {@code tasks} copies of {@code body} on virtual threads, all released at the same moment. */
    private static void runConcurrently(int tasks, IntTask body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(tasks);
            for (int i = 0; i < tasks; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    body.run(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(); // surfaces any exception thrown inside a task
            }
        }
    }

    @FunctionalInterface
    private interface IntTask {
        void run(int index) throws Exception;
    }
}
