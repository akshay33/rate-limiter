package io.github.akshay.ratelimiter;

/**
 * Rate-limit state for one key, held by {@link InMemoryKeyedRateLimiter}.
 * Accessed only inside the map's per-key {@code compute} calls, which serialize access to it.
 */
interface KeyState {

    /** Tries to take {@code permits}; on rejection, reports how long until it could succeed. */
    RateLimitDecision decide(int permits);

    /** True when this state is indistinguishable from a fresh one, so it can be dropped. */
    boolean isIdle();
}
