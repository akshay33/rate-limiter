package io.github.akshay.ratelimiter;

import java.time.Duration;
import java.util.Objects;

/**
 * Which algorithm to use and its settings. Each algorithm has its own record, so only valid
 * combinations can be expressed. Build a limiter from it with {@link RateLimiters}.
 */
public sealed interface RateLimitConfig {

    /** Max requests allowed: the bucket capacity, or the number per window. */
    long limit();

    /** Burst up to {@code capacity}, refilling {@code refillTokens} every {@code refillPeriod}. */
    static RateLimitConfig tokenBucket(long capacity, long refillTokens, Duration refillPeriod) {
        return new TokenBucket(capacity, refillTokens, refillPeriod);
    }

    /** At most {@code limit} requests in any {@code window}; exact, stores one timestamp per request. */
    static RateLimitConfig slidingWindowLog(long limit, Duration window) {
        return new SlidingWindowLog(limit, window);
    }

    /** At most about {@code limit} requests in any {@code window}; two counters per key. */
    static RateLimitConfig slidingWindowCounter(long limit, Duration window) {
        return new SlidingWindowCounter(limit, window);
    }

    record TokenBucket(long capacity, long refillTokens, Duration refillPeriod) implements RateLimitConfig {
        public TokenBucket {
            Objects.requireNonNull(refillPeriod, "refillPeriod");
            TokenBucketRateLimiter.validateConfig(capacity, refillTokens, refillPeriod);
        }

        @Override
        public long limit() {
            return capacity;
        }
    }

    record SlidingWindowLog(long limit, Duration window) implements RateLimitConfig {
        public SlidingWindowLog {
            validateWindow(limit, window);
        }
    }

    record SlidingWindowCounter(long limit, Duration window) implements RateLimitConfig {
        public SlidingWindowCounter {
            validateWindow(limit, window);
        }
    }

    private static void validateWindow(long limit, Duration window) {
        Objects.requireNonNull(window, "window");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }
}
