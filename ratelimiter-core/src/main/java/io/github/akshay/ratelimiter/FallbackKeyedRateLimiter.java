package io.github.akshay.ratelimiter;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uses a primary limiter (e.g. Redis) and switches to a fallback (e.g. in-memory) whenever the
 * primary is unavailable, so an outage loosens limits instead of failing every request.
 *
 * <p>Only {@link RateLimiterUnavailableException} triggers the fallback; argument errors still
 * propagate. Each call tries the primary first, so recovery is automatic.
 */
public final class FallbackKeyedRateLimiter implements KeyedRateLimiter {

    private static final Logger LOG = System.getLogger(FallbackKeyedRateLimiter.class.getName());

    private final KeyedRateLimiter primary;
    private final KeyedRateLimiter fallback;
    private final AtomicBoolean usingFallback = new AtomicBoolean(false);

    public FallbackKeyedRateLimiter(KeyedRateLimiter primary, KeyedRateLimiter fallback) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public RateLimitDecision tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int permits) {
        try {
            RateLimitDecision decision = primary.tryAcquire(key, permits);
            // Log only the transition, not every request.
            if (usingFallback.compareAndSet(true, false)) {
                LOG.log(Level.INFO, "Primary rate limiter recovered; leaving fallback");
            }
            return decision;
        } catch (RateLimiterUnavailableException e) {
            if (usingFallback.compareAndSet(false, true)) {
                LOG.log(Level.WARNING, "Primary rate limiter unavailable; using fallback", e);
            }
            return fallback.tryAcquire(key, permits);
        }
    }

    /** True while the last call had to use the fallback. */
    public boolean isUsingFallback() {
        return usingFallback.get();
    }
}
