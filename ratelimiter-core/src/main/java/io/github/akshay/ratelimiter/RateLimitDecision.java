package io.github.akshay.ratelimiter;

import java.time.Duration;
import java.util.Objects;

/**
 * Outcome of a rate-limit check.
 *
 * @param allowed    whether the request may proceed
 * @param retryAfter how long until it could succeed; {@link Duration#ZERO} when allowed
 */
public record RateLimitDecision(boolean allowed, Duration retryAfter) {

    private static final RateLimitDecision ALLOWED = new RateLimitDecision(true, Duration.ZERO);

    public RateLimitDecision {
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        if (allowed && !retryAfter.isZero()) {
            throw new IllegalArgumentException("an allowed decision has no retryAfter");
        }
    }

    public static RateLimitDecision allow() {
        return ALLOWED;
    }

    public static RateLimitDecision reject(Duration retryAfter) {
        return new RateLimitDecision(false, retryAfter);
    }
}
