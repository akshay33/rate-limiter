package io.github.akshay.ratelimiter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.akshay.ratelimiter.TokenBucketRateLimiterTest.assertDurationNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Limit 5 per 10 s window; the clock starts at the beginning of window 0. */
class SlidingWindowCounterTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);

    private final AtomicLong clock = new AtomicLong(0);
    private final InMemoryKeyedRateLimiter limiter = new InMemoryKeyedRateLimiter(
            RateLimitConfig.slidingWindowCounter(5, WINDOW), clock::get, Duration.ofSeconds(30));

    @Test
    void allowsTheLimitWithinAWindowThenRejects() {
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("ip").allowed(), "request " + (i + 1));
        }
        assertFalse(limiter.tryAcquire("ip").allowed());
    }

    @Test
    void previousWindowCountsInProportionToItsOverlap() {
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("ip");        // 4 requests in window 0
        }
        advanceTo(Duration.ofSeconds(15));   // halfway through window 1: those 4 count as 2

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("ip").allowed(), "estimate " + (2 + i) + " + 1 <= 5");
        }
        assertFalse(limiter.tryAcquire("ip").allowed(), "estimate 3 + 2 = 5, no room for another");
    }

    @Test
    void retryAfterWithinTheCurrentWindow() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("ip");        // window 0 full
        }
        advanceTo(Duration.ofSeconds(12));   // previous weight 0.8 → estimate 4
        assertTrue(limiter.tryAcquire("ip").allowed()); // estimate 5 now

        // Needs 1 + 5 × (1 - t/10) <= 4, i.e. t >= 4 s into window 1: 2 s from now.
        assertDurationNear(Duration.ofSeconds(2), limiter.tryAcquire("ip").retryAfter());
        advanceTo(Duration.ofSeconds(14));
        assertTrue(limiter.tryAcquire("ip").allowed());
    }

    @Test
    void retryAfterReachingIntoTheNextWindow() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("ip");        // window 0 full
        }
        advanceTo(Duration.ofSeconds(3));

        // Next window: 5 × (1 - t/10) <= 4 at t = 2 s, so 7 s (rest of window 0) + 2 s.
        assertDurationNear(Duration.ofSeconds(9), limiter.tryAcquire("ip").retryAfter());
        advanceTo(Duration.ofSeconds(12));
        assertTrue(limiter.tryAcquire("ip").allowed());
    }

    @Test
    void windowsOlderThanThePreviousOneNoLongerCount() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("ip");        // window 0
        }
        advanceTo(Duration.ofSeconds(25));   // window 2: window 0 is too old to count

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("ip").allowed());
        }
    }

    @Test
    void sweepRemovesKeysWithNoRecentRequests() {
        limiter.tryAcquire("a");
        advanceTo(Duration.ofSeconds(31));   // two windows later, past the sweep interval
        limiter.tryAcquire("b");

        assertEquals(1, limiter.size());
    }

    @Test
    void concurrentRequestsGetExactlyTheLimit() throws Exception {
        InMemoryKeyedRateLimiter big = new InMemoryKeyedRateLimiter(
                RateLimitConfig.slidingWindowCounter(100, WINDOW), clock::get, Duration.ZERO);
        AtomicInteger granted = new AtomicInteger();

        ConcurrencySupport.runConcurrently(2_000, i -> {
            if (big.tryAcquire("ip").allowed()) {
                granted.incrementAndGet();
            }
        });

        assertEquals(100, granted.get());
    }

    private void advanceTo(Duration time) {
        clock.set(time.toNanos());
    }
}
