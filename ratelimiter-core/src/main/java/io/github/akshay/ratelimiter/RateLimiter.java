package io.github.akshay.ratelimiter;

/** Limits how often an operation may proceed, based on a budget of permits. */
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
