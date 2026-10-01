package io.github.akshay.ratelimiter.redis;

import io.github.akshay.ratelimiter.KeyedRateLimiter;
import io.github.akshay.ratelimiter.RateLimitDecision;
import io.github.akshay.ratelimiter.RateLimiterUnavailableException;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * {@link KeyedRateLimiter} whose buckets live in Redis, so every application instance
 * sharing the same Redis enforces one combined limit per key.
 *
 * <p>Refill and deduct run atomically in a Lua script using Redis's clock. Idle buckets
 * expire on their own once they would have refilled completely.
 *
 * <p>The caller owns the connection: this class never closes it. Configure a short command
 * timeout on it (e.g. 100 ms) and reject commands while disconnected, so an outage fails fast
 * with {@link RateLimiterUnavailableException} instead of making requests wait.
 */
public final class RedisTokenBucketRateLimiter implements KeyedRateLimiter {

    static final String KEY_PREFIX = "ratelimit:";
    private static final long EXPIRY_BUFFER_MS = 1_000;

    private final RedisScript script;
    private final long capacity;
    private final String capacityArg;
    private final String ratePerMicroArg;
    private final String expiryMsArg;

    /**
     * @param connection   Lettuce connection, shared safely across threads
     * @param capacity     max tokens in each key's bucket
     * @param refillTokens tokens added per period
     * @param refillPeriod how often tokens are added
     */
    public RedisTokenBucketRateLimiter(StatefulRedisConnection<String, String> connection,
                                       long capacity, long refillTokens, Duration refillPeriod) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(refillPeriod, "refillPeriod");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (refillTokens <= 0) {
            throw new IllegalArgumentException("refillTokens must be positive");
        }
        if (refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("refillPeriod must be positive");
        }
        this.script = new RedisScript(connection.sync(), "token_bucket.lua");
        this.capacity = capacity;

        double ratePerMicro = refillTokens / (refillPeriod.toNanos() / 1_000.0);
        long fullRefillMs = (long) Math.ceil(capacity / ratePerMicro / 1_000.0);
        this.capacityArg = Long.toString(capacity);
        this.ratePerMicroArg = Double.toString(ratePerMicro);
        this.expiryMsArg = Long.toString(fullRefillMs + EXPIRY_BUFFER_MS);
    }

    @Override
    public RateLimitDecision tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int permits) {
        Objects.requireNonNull(key, "key");
        RedisRateLimiters.validatePermits(permits, capacity);
        List<Long> result = script.run(KEY_PREFIX + key, capacityArg, ratePerMicroArg, Integer.toString(permits), expiryMsArg);
        return RedisRateLimiters.decision(result);
    }
}
