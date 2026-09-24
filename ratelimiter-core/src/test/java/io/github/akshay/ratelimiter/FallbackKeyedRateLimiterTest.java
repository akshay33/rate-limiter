package io.github.akshay.ratelimiter;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackKeyedRateLimiterTest {

    private final SwitchablePrimary primary = new SwitchablePrimary();
    private final KeyedRateLimiter fallbackStore = new InMemoryKeyedRateLimiter(2, 1, Duration.ofMinutes(1));
    private final FallbackKeyedRateLimiter limiter = new FallbackKeyedRateLimiter(primary, fallbackStore);

    @Test
    void usesPrimaryWhileItIsAvailable() {
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertEquals(1, primary.calls);
        assertFalse(limiter.isUsingFallback());
    }

    @Test
    void usesFallbackWhenPrimaryIsUnavailable() {
        primary.down = true;

        // The fallback enforces its own limit (capacity 2) instead of allowing everything.
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertFalse(limiter.tryAcquire("ip").allowed());
        assertTrue(limiter.isUsingFallback());
    }

    @Test
    void returnsToPrimaryWhenItRecovers() {
        primary.down = true;
        limiter.tryAcquire("ip");
        assertTrue(limiter.isUsingFallback());

        primary.down = false;
        assertTrue(limiter.tryAcquire("ip").allowed());
        assertFalse(limiter.isUsingFallback());
    }

    @Test
    void argumentErrorsAreNotHiddenByTheFallback() {
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("ip", 0));
    }

    /** Stand-in for a remote limiter that can be switched off to simulate an outage. */
    private static final class SwitchablePrimary implements KeyedRateLimiter {
        boolean down;
        int calls;

        @Override
        public RateLimitDecision tryAcquire(String key) {
            return tryAcquire(key, 1);
        }

        @Override
        public RateLimitDecision tryAcquire(String key, int permits) {
            if (permits <= 0) {
                throw new IllegalArgumentException("permits must be positive");
            }
            if (down) {
                throw new RateLimiterUnavailableException("simulated outage", new RuntimeException());
            }
            calls++;
            return RateLimitDecision.allow();
        }
    }
}
