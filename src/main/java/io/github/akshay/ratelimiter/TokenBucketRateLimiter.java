package io.github.akshay.ratelimiter;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Thread-safe token bucket rate limiter.
 *
 * <p>The bucket starts full with {@code capacity} tokens. Each call to
 * {@link #tryAcquire()} first lazily refills the bucket based on elapsed
 * time since the last refill, then attempts to deduct the requested number
 * of permits. Refilling is lazy (computed on demand) rather than driven by
 * a background thread, so the limiter has no extra threads or timers to
 * manage.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final long capacity;
    private final double refillTokensPerNano;
    private final LongSupplier nanoTimeSource;
    private final ReentrantLock lock = new ReentrantLock();

    private double availableTokens;
    private long lastRefillNanos;

    /**
     * @param capacity     maximum number of tokens the bucket can hold
     * @param refillTokens number of tokens added per {@code refillPeriod}
     * @param refillPeriod the period over which {@code refillTokens} are added
     */
    public TokenBucketRateLimiter(long capacity, long refillTokens, Duration refillPeriod) {
        this(capacity, refillTokens, refillPeriod, System::nanoTime);
    }

    /**
     * Package-private constructor allowing injection of the time source, so
     * refill behavior can be tested deterministically without real sleeps.
     */
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
