package io.github.akshay.ratelimiter.redis;

import io.github.akshay.ratelimiter.KeyedRateLimiter;
import io.github.akshay.ratelimiter.RateLimitDecision;
import io.github.akshay.ratelimiter.RateLimiterUnavailableException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
    private static final String SCRIPT = loadScript("token_bucket.lua");
    private static final long EXPIRY_BUFFER_MS = 1_000;

    private final RedisCommands<String, String> redis;
    private final long capacity;
    private final String capacityArg;
    private final String ratePerMicroArg;
    private final String expiryMsArg;
    private final String scriptSha;

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
        this.redis = connection.sync();
        this.capacity = capacity;

        double ratePerMicro = refillTokens / (refillPeriod.toNanos() / 1_000.0);
        long fullRefillMs = (long) Math.ceil(capacity / ratePerMicro / 1_000.0);
        this.capacityArg = Long.toString(capacity);
        this.ratePerMicroArg = Double.toString(ratePerMicro);
        this.expiryMsArg = Long.toString(fullRefillMs + EXPIRY_BUFFER_MS);
        this.scriptSha = redis.digest(SCRIPT);
    }

    @Override
    public RateLimitDecision tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int permits) {
        Objects.requireNonNull(key, "key");
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive");
        }
        if (permits > capacity) {
            throw new IllegalArgumentException("permits must not exceed capacity (" + capacity + ")");
        }

        List<Long> result = runScript(KEY_PREFIX + key, Integer.toString(permits));
        boolean allowed = result.get(0) == 1L;
        if (allowed) {
            return RateLimitDecision.allow();
        }
        return RateLimitDecision.reject(Duration.ofNanos(result.get(1) * 1_000));
    }

    private List<Long> runScript(String redisKey, String permitsArg) {
        String[] keys = {redisKey};
        String[] args = {capacityArg, ratePerMicroArg, permitsArg, expiryMsArg};
        try {
            try {
                // Send only the script's hash; Redis runs its cached copy.
                return redis.evalsha(scriptSha, ScriptOutputType.MULTI, keys, args);
            } catch (RedisNoScriptException e) {
                // Script cache is empty (Redis restarted or was flushed): send the full script, which re-caches it.
                return redis.eval(SCRIPT, ScriptOutputType.MULTI, keys, args);
            }
        } catch (RedisException e) {
            // Connection lost, timed out, etc. Callers can fall back (see FallbackKeyedRateLimiter).
            throw new RateLimiterUnavailableException("Redis rate limiter unavailable", e);
        }
    }

    private static String loadScript(String name) {
        try (InputStream in = RedisTokenBucketRateLimiter.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing script resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
