package io.github.akshay.ratelimiter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.akshay.ratelimiter.TokenBucketRateLimiterTest.assertDurationNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowLogTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);

    private final AtomicLong clock = new AtomicLong(0);
    private final InMemoryKeyedRateLimiter limiter = new InMemoryKeyedRateLimiter(
            RateLimitConfig.slidingWindowLog(3, WINDOW), clock::get, Duration.ofSeconds(30));

    @Test
    void allowsTheLimitWithinAWindowThenRejects() {
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertFalse(limiter.tryAcquire("ip").allowed());
    }

    @Test
    void requestsLeaveTheWindowExactlyOneWindowLater() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("ip");
        }
        advance(Duration.ofMillis(9_999));
        assertFalse(limiter.tryAcquire("ip").allowed(), "still inside the window");

        advance(Duration.ofMillis(1));
        assertTrue(limiter.tryAcquire("ip").allowed(), "a full window has passed");
    }

    @Test
    void theWindowSlidesRatherThanResetting() {
        limiter.tryAcquire("ip");            // t=0
        advance(Duration.ofSeconds(5));
        limiter.tryAcquire("ip");            // t=5
        limiter.tryAcquire("ip");            // t=5
        advance(Duration.ofSeconds(5));      // t=10: only the t=0 request has left

        assertTrue(limiter.tryAcquire("ip").allowed());
        assertFalse(limiter.tryAcquire("ip").allowed(), "the t=5 requests are still in the window");
    }

    @Test
    void retryAfterIsWhenEnoughOldRequestsLeave() {
        limiter.tryAcquire("ip");            // t=0
        advance(Duration.ofSeconds(4));
        limiter.tryAcquire("ip", 2);         // t=4, window now full
        advance(Duration.ofSeconds(2));      // t=6

        // One permit frees up when the t=0 entry leaves at t=10.
        assertDurationNear(Duration.ofSeconds(4), limiter.tryAcquire("ip").retryAfter());
        // Two permits need the t=4 entries gone too, at t=14.
        assertDurationNear(Duration.ofSeconds(8), limiter.tryAcquire("ip", 2).retryAfter());
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }
        assertFalse(limiter.tryAcquire("a").allowed());
        assertTrue(limiter.tryAcquire("b").allowed());
    }

    @Test
    void sweepRemovesKeysWhoseWindowIsEmpty() {
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        advance(Duration.ofSeconds(31)); // past the window and the sweep interval
        limiter.tryAcquire("c");

        assertEquals(1, limiter.size());
    }

    @Test
    void concurrentRequestsGetExactlyTheLimit() throws Exception {
        InMemoryKeyedRateLimiter big = new InMemoryKeyedRateLimiter(
                RateLimitConfig.slidingWindowLog(100, WINDOW), clock::get, Duration.ZERO);
        AtomicInteger granted = new AtomicInteger();

        ConcurrencySupport.runConcurrently(2_000, i -> {
            if (big.tryAcquire("ip").allowed()) {
                granted.incrementAndGet();
            }
        });

        assertEquals(100, granted.get());
    }

    @Test
    void rejectsMorePermitsThanTheLimit() {
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("ip", 4));
    }

    private void advance(Duration duration) {
        clock.addAndGet(duration.toNanos());
    }
}
