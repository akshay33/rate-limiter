package io.github.akshay.ratelimiter.redis;

import io.github.akshay.ratelimiter.KeyedRateLimiter;
import io.github.akshay.ratelimiter.RateLimitConfig;
import io.github.akshay.ratelimiter.RateLimitDecision;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Builds Redis-backed rate limiters from a {@link RateLimitConfig}. */
public final class RedisRateLimiters {

    private RedisRateLimiters() {
    }

    /** A limiter shared by every instance using the same Redis, with the configured algorithm. */
    public static KeyedRateLimiter create(StatefulRedisConnection<String, String> connection, RateLimitConfig config) {
        Objects.requireNonNull(config, "config");
        return switch (config) {
            case RateLimitConfig.TokenBucket c ->
                    new RedisTokenBucketRateLimiter(connection, c.capacity(), c.refillTokens(), c.refillPeriod());
            case RateLimitConfig.SlidingWindowLog c -> new RedisSlidingWindowLogRateLimiter(connection, c.limit(), c.window());
            case RateLimitConfig.SlidingWindowCounter c -> new RedisSlidingWindowCounterRateLimiter(connection, c.limit(), c.window());
        };
    }

    static void validatePermits(int permits, long limit) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive");
        }
        if (permits > limit) {
            throw new IllegalArgumentException("permits must not exceed the limit (" + limit + ")");
        }
    }

    static void validateWindow(long limit, Duration window) {
        Objects.requireNonNull(window, "window");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }

    /** Scripts return {allowed (1/0), retry-after in microseconds}. */
    static RateLimitDecision decision(List<Long> result) {
        if (result.get(0) == 1L) {
            return RateLimitDecision.allow();
        }
        return RateLimitDecision.reject(Duration.ofNanos(result.get(1) * 1_000));
    }
}
