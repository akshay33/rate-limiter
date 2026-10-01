package io.github.akshay.ratelimiter.redis;

import io.github.akshay.ratelimiter.KeyedRateLimiter;
import io.github.akshay.ratelimiter.RateLimitDecision;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;
import java.util.Objects;

/**
 * Sliding window counter in Redis: current and previous window counts per key, shared by every
 * instance. Constant memory per key; near-exact.
 */
public final class RedisSlidingWindowCounterRateLimiter implements KeyedRateLimiter {

    static final String KEY_PREFIX = "ratelimit:sw-counter:";
    private static final long EXPIRY_BUFFER_MS = 1_000;

    private final RedisScript script;
    private final long limit;
    private final String limitArg;
    private final String windowMicrosArg;
    private final String expiryMsArg;

    public RedisSlidingWindowCounterRateLimiter(StatefulRedisConnection<String, String> connection, long limit, Duration window) {
        Objects.requireNonNull(connection, "connection");
        RedisRateLimiters.validateWindow(limit, window);
        this.script = new RedisScript(connection.sync(), "sliding_window_counter.lua");
        this.limit = limit;
        this.limitArg = Long.toString(limit);
        this.windowMicrosArg = Long.toString(window.toNanos() / 1_000);
        this.expiryMsArg = Long.toString(2 * window.toMillis() + EXPIRY_BUFFER_MS);
    }

    @Override
    public RateLimitDecision tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int permits) {
        Objects.requireNonNull(key, "key");
        RedisRateLimiters.validatePermits(permits, limit);
        return RedisRateLimiters.decision(script.run(KEY_PREFIX + key,
                limitArg, windowMicrosArg, Integer.toString(permits), expiryMsArg));
    }
}
