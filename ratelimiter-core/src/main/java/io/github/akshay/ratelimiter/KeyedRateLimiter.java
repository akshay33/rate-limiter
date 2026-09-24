package io.github.akshay.ratelimiter;

/** Rate limiter with a separate budget of permits per key, such as a client IP. */
public interface KeyedRateLimiter {

    /** Attempts to acquire one permit for {@code key} without blocking. */
    RateLimitDecision tryAcquire(String key);

    /**
     * Attempts to acquire {@code permits} for {@code key} without blocking.
     *
     * @throws IllegalArgumentException        if {@code permits} is not positive or exceeds the bucket capacity
     * @throws RateLimiterUnavailableException if a backing store (e.g. Redis) can't be reached
     */
    RateLimitDecision tryAcquire(String key, int permits);
}
