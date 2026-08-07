package io.github.akshay.ratelimiter;

/**
 * A rate limiter that controls how frequently an operation is allowed to
 * proceed, based on a configurable budget of permits.
 */
public interface RateLimiter {

    /**
     * Attempts to acquire a single permit without blocking.
     *
     * @return {@code true} if the permit was acquired, {@code false} if the
     *         caller should be rejected/throttled.
     */
    boolean tryAcquire();

    /**
     * Attempts to acquire the given number of permits without blocking.
     *
     * @param permits number of permits to acquire, must be positive
     * @return {@code true} if all permits were acquired, {@code false} if
     *         the caller should be rejected/throttled.
     */
    boolean tryAcquire(int permits);
}
