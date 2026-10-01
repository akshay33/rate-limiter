package io.github.akshay.ratelimiter.redis;

import io.github.akshay.ratelimiter.RateLimiterUnavailableException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** A Lua script from the classpath, run by its hash, resent if Redis has forgotten it. */
final class RedisScript {

    private final RedisCommands<String, String> redis;
    private final String source;
    private final String sha;

    RedisScript(RedisCommands<String, String> redis, String resourceName) {
        this.redis = redis;
        this.source = load(resourceName);
        this.sha = redis.digest(source);
    }

    /** Runs the script on one key; Redis failures become {@link RateLimiterUnavailableException}. */
    List<Long> run(String key, String... args) {
        String[] keys = {key};
        try {
            try {
                return redis.evalsha(sha, ScriptOutputType.MULTI, keys, args);
            } catch (RedisNoScriptException e) {
                // Script cache is empty (Redis restarted or was flushed): send the full script, which re-caches it.
                return redis.eval(source, ScriptOutputType.MULTI, keys, args);
            }
        } catch (RedisException e) {
            // Connection lost, timed out, etc. Callers can fall back (see FallbackKeyedRateLimiter).
            throw new RateLimiterUnavailableException("Redis rate limiter unavailable", e);
        }
    }

    private static String load(String name) {
        try (InputStream in = RedisScript.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing script resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
