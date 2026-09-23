package io.github.akshay.ratelimiter;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Thread-safe token bucket rate limiter.
 *
 * Starts full and refills lazily on each {@link #tryAcquire()} call, so no background thread is needed.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final long capacity;
    private final double refillTokensPerNano;
    private final LongSupplier nanoTimeSource;
    private final ReentrantLock lock = new ReentrantLock();

    private double availableTokens;
    private long lastRefillNanos;

    /**
     * @param capacity     max tokens in the bucket
     * @param refillTokens tokens added per period
     * @param refillPeriod how often tokens are added
     */
    public TokenBucketRateLimiter(long capacity, long refillTokens, Duration refillPeriod) {
        this(capacity, refillTokens, refillPeriod, System::nanoTime);
    }

    /** Accepts a custom time source so tests can control time instead of sleeping. */
    TokenBucketRateLimiter(long capacity, long refillTokens, Duration refillPeriod, LongSupplier nanoTimeSource) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (refillTokens <= 0) {
            throw new IllegalArgumentException("refillTokens must be positive");
        }
        if (refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("refillPeriod must be positive");
        }
        this.capacity = capacity;
        this.refillTokensPerNano = (double) refillTokens / refillPeriod.toNanos();
        this.nanoTimeSource = nanoTimeSource;
        this.availableTokens = capacity;
        this.lastRefillNanos = nanoTimeSource.getAsLong();
    }

    @Override
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    @Override
    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive");
        }
        lock.lock();
        try {
            refill();
            if (availableTokens >= permits) {
                availableTokens -= permits;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Must be called while holding {@code lock}. */
    private void refill() {
        long now = nanoTimeSource.getAsLong();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos <= 0) {
            return;
        }
        double replenished = elapsedNanos * refillTokensPerNano;
        availableTokens = Math.min(capacity, availableTokens + replenished);
        lastRefillNanos = now;
    }
}
