package io.github.akshay.ratelimiter;

/** Builds rate limiters from a {@link RateLimitConfig}. */
public final class RateLimiters {

    private RateLimiters() {
    }

    /** An in-memory limiter with separate state per key, using the configured algorithm. */
    public static KeyedRateLimiter inMemory(RateLimitConfig config) {
        return new InMemoryKeyedRateLimiter(config, System::nanoTime, InMemoryKeyedRateLimiter.DEFAULT_SWEEP_INTERVAL);
    }
}
