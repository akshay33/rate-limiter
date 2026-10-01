package io.github.akshay.ratelimiter;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitConfigTest {

    static Stream<RateLimitConfig> everyAlgorithm() {
        return Stream.of(
                RateLimitConfig.tokenBucket(3, 1, Duration.ofMinutes(1)),
                RateLimitConfig.slidingWindowLog(3, Duration.ofMinutes(1)),
                RateLimitConfig.slidingWindowCounter(3, Duration.ofMinutes(1)));
    }

    @ParameterizedTest
    @MethodSource("everyAlgorithm")
    void factoryBuildsALimiterThatEnforcesTheLimit(RateLimitConfig config) {
        KeyedRateLimiter limiter = RateLimiters.inMemory(config);

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("ip").allowed());
        }
        RateLimitDecision rejected = limiter.tryAcquire("ip");
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfter().compareTo(Duration.ZERO) > 0);
        assertEquals(3, config.limit());
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RateLimitConfig.slidingWindowLog(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> RateLimitConfig.slidingWindowCounter(5, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> RateLimitConfig.slidingWindowLog(5, Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> RateLimitConfig.slidingWindowCounter(5, null));
        assertThrows(IllegalArgumentException.class, () -> RateLimitConfig.tokenBucket(0, 1, Duration.ofSeconds(1)));
    }
}
