package io.github.akshay.gateway;

import io.github.akshay.ratelimiter.RateLimitConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code ratelimit.*} settings.
 *
 * @param mode              {@code redis} (shared across gateways, in-memory fallback) or {@code memory} (per gateway)
 * @param algorithm         {@code token-bucket}, {@code sliding-window-log} or {@code sliding-window-counter}
 * @param limit             max requests: the bucket capacity, or the number per window
 * @param window            window length (sliding window algorithms)
 * @param refillTokens      requests regained per {@code refillPeriod} (token bucket)
 * @param refillPeriod      how often {@code refillTokens} are regained (token bucket)
 * @param trustForwardedFor read the client IP from {@code X-Forwarded-For}; only safe behind a proxy that overwrites it
 * @param redis             Redis connection settings
 */
@ConfigurationProperties("ratelimit")
public record RateLimitProperties(
        @DefaultValue("redis") Mode mode,
        @DefaultValue("token-bucket") Algorithm algorithm,
        @DefaultValue("5") long limit,
        @DefaultValue("10s") Duration window,
        @DefaultValue("1") long refillTokens,
        @DefaultValue("2s") Duration refillPeriod,
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue Redis redis) {

    public enum Mode { REDIS, MEMORY }

    public enum Algorithm { TOKEN_BUCKET, SLIDING_WINDOW_LOG, SLIDING_WINDOW_COUNTER }

    /** The library config for the chosen algorithm. */
    public RateLimitConfig toConfig() {
        return switch (algorithm) {
            case TOKEN_BUCKET -> RateLimitConfig.tokenBucket(limit, refillTokens, refillPeriod);
            case SLIDING_WINDOW_LOG -> RateLimitConfig.slidingWindowLog(limit, window);
            case SLIDING_WINDOW_COUNTER -> RateLimitConfig.slidingWindowCounter(limit, window);
        };
    }

    /**
     * @param url               Redis URL
     * @param timeout           max wait for a Redis reply before falling back
     * @param reconnectInterval how often to retry when Redis wasn't reachable at startup
     */
    public record Redis(
            @DefaultValue("redis://localhost:6379") String url,
            @DefaultValue("100ms") Duration timeout,
            @DefaultValue("2s") Duration reconnectInterval) {
    }
}
