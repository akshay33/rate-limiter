package io.github.akshay.ratelimiter.redis;

import io.github.akshay.ratelimiter.KeyedRateLimiter;
import io.github.akshay.ratelimiter.RateLimitDecision;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Sliding window log in Redis: a sorted set of request timestamps per key, shared by every
 * instance. Exact; memory per key grows with the limit.
 */
public final class RedisSlidingWindowLogRateLimiter implements KeyedRateLimiter {

    static final String KEY_PREFIX = "ratelimit:sw-log:";
    private static final long EXPIRY_BUFFER_MS = 1_000;

    private final RedisScript script;
    private final long limit;
    private final String limitArg;
    private final String windowMicrosArg;
    private final String expiryMsArg;

    public RedisSlidingWindowLogRateLimiter(StatefulRedisConnection<String, String> connection, long limit, Duration window) {
        Objects.requireNonNull(connection, "connection");
        RedisRateLimiters.validateWindow(limit, window);
        this.script = new RedisScript(connection.sync(), "sliding_window_log.lua");
        this.limit = limit;
        this.limitArg = Long.toString(limit);
        this.windowMicrosArg = Long.toString(window.toNanos() / 1_000);
        this.expiryMsArg = Long.toString(window.toMillis() + EXPIRY_BUFFER_MS);
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
                limitArg, windowMicrosArg, Integer.toString(permits), UUID.randomUUID().toString(), expiryMsArg));
    }
}
