package io.github.akshay.ratelimiter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketRateLimiterTest {

    @Test
    void allowsRequestsUpToCapacityThenRejects() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new TokenBucketRateLimiter(3, 1, Duration.ofSeconds(1), clock::get);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void refillsTokensAfterElapsedTime() {
        AtomicLong clock = new AtomicLong(0);
        // capacity 2, refills 1 token per second
        RateLimiter limiter = new TokenBucketRateLimiter(2, 1, Duration.ofSeconds(1), clock::get);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        // advance the fake clock by 1 second worth of nanos
        clock.addAndGet(Duration.ofSeconds(1).toNanos());

        assertTrue(limiter.tryAcquire(), "should have refilled one token after 1 second");
        assertFalse(limiter.tryAcquire(), "bucket should be empty again");
    }

    @Test
    void refillNeverExceedsCapacity() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new TokenBucketRateLimiter(2, 1, Duration.ofSeconds(1), clock::get);

        // advance far beyond what's needed to refill even a huge number of tokens
        clock.addAndGet(Duration.ofDays(1).toNanos());

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "bucket should be capped at capacity, not overflowed");
    }

    @Test
    void tryAcquireMultiplePermits() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new TokenBucketRateLimiter(5, 1, Duration.ofSeconds(1), clock::get);

        assertTrue(limiter.tryAcquire(3));
        assertFalse(limiter.tryAcquire(3), "only 2 tokens left, should reject request for 3");
        assertTrue(limiter.tryAcquire(2));
    }

    @Test
    void rejectsNonPositivePermits() {
        RateLimiter limiter = new TokenBucketRateLimiter(5, 1, Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(-1));
    }

    @Test
    void rejectsPermitsAboveCapacity() {
        RateLimiter limiter = new TokenBucketRateLimiter(5, 1, Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(6));
    }

    @Test
    void rejectionReportsTimeUntilEnoughTokensRefill() {
        AtomicLong clock = new AtomicLong(0);
        // capacity 2, refills 1 token per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 1, Duration.ofSeconds(1), clock::get);

        assertTrue(limiter.decide(2).allowed());

        RateLimitDecision rejected = limiter.decide(2);
        assertFalse(rejected.allowed());
        assertDurationNear(Duration.ofSeconds(2), rejected.retryAfter());

        clock.addAndGet(Duration.ofMillis(500).toNanos());
        assertDurationNear(Duration.ofMillis(1500), limiter.decide(2).retryAfter());
    }

    @Test
    void allowedDecisionHasNoRetryAfter() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, Duration.ofSeconds(1));
        assertEquals(Duration.ZERO, limiter.decide(1).retryAfter());
    }

    /** Refill math uses doubles, so allow a microsecond of rounding. */
    static void assertDurationNear(Duration expected, Duration actual) {
        long diffNanos = Math.abs(expected.toNanos() - actual.toNanos());
        assertTrue(diffNanos <= 1_000, "expected ~" + expected + " but was " + actual);
    }

    @Test
    void concurrentAcquiresNeverExceedCapacity() throws InterruptedException {
        int capacity = 1_000;
        int threadCount = 5_000;

        // frozen clock: no refill happens during the test, isolating the
        // invariant we care about (deductions are never lost or double-counted)
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new TokenBucketRateLimiter(capacity, 1, Duration.ofSeconds(1), clock::get);

        // one virtual thread per task avoids pool-starvation deadlocks that a
        // small fixed pool would hit against the ready/start rendezvous below
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (limiter.tryAcquire()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "threads did not finish in time");
        executor.shutdown();

        assertEquals(capacity, successCount.get(),
                "exactly `capacity` acquires should succeed under concurrent load, no more and no fewer");
    }
}
