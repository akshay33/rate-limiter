package io.github.akshay.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code ratelimit.*} settings.
 *
 * @param mode              {@code redis} (shared across gateways, in-memory fallback) or {@code memory} (per gateway)
 * @param capacity          max requests in a burst
 * @param refillTokens      requests regained per {@code refillPeriod}
 * @param refillPeriod      how often {@code refillTokens} are regained
 * @param trustForwardedFor read the client IP from {@code X-Forwarded-For}; only safe behind a proxy that overwrites it
 * @param redis             Redis connection settings
 */
@ConfigurationProperties("ratelimit")
public record RateLimitProperties(
        @DefaultValue("redis") Mode mode,
        @DefaultValue("5") long capacity,
        @DefaultValue("1") long refillTokens,
        @DefaultValue("2s") Duration refillPeriod,
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue Redis redis) {

    public enum Mode { REDIS, MEMORY }

    /**
     * @param url              Redis URL
     * @param timeout          max wait for a Redis reply before falling back
     * @param reconnectInterval how often to retry when Redis wasn't reachable at startup
     */
    public record Redis(
            @DefaultValue("redis://localhost:6379") String url,
            @DefaultValue("100ms") Duration timeout,
            @DefaultValue("2s") Duration reconnectInterval) {
    }
}
