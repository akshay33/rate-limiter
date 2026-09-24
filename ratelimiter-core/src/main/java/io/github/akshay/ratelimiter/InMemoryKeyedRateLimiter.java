package io.github.akshay.ratelimiter;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * In-memory {@link KeyedRateLimiter} with one token bucket per key.
 *
 * <p>State lives in this process only, so each server in a cluster enforces its own limit.
 * Buckets that have fully refilled are removed during periodic sweeps, which run on the
 * calling thread (no background thread).
 */
public final class InMemoryKeyedRateLimiter implements KeyedRateLimiter {

    static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofSeconds(30);

    private final long capacity;
    private final long refillTokens;
    private final Duration refillPeriod;
    private final LongSupplier nanoTimeSource;
    private final long sweepIntervalNanos;

    private final ConcurrentHashMap<String, TokenBucketRateLimiter> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepNanos;

    /**
     * @param capacity     max tokens in each key's bucket
     * @param refillTokens tokens added per period
     * @param refillPeriod how often tokens are added
     */
    public InMemoryKeyedRateLimiter(long capacity, long refillTokens, Duration refillPeriod) {
        this(capacity, refillTokens, refillPeriod, System::nanoTime, DEFAULT_SWEEP_INTERVAL);
    }

    /** Accepts a custom time source and sweep interval so tests can control both. */
    InMemoryKeyedRateLimiter(long capacity, long refillTokens, Duration refillPeriod,
                             LongSupplier nanoTimeSource, Duration sweepInterval) {
        TokenBucketRateLimiter.validateConfig(capacity, refillTokens, refillPeriod);
        if (sweepInterval.isNegative()) {
            throw new IllegalArgumentException("sweepInterval must not be negative");
        }
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriod = refillPeriod;
        this.nanoTimeSource = nanoTimeSource;
        this.sweepIntervalNanos = sweepInterval.toNanos();
        this.lastSweepNanos = new AtomicLong(nanoTimeSource.getAsLong());
    }

    @Override
    public RateLimitDecision tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int permits) {
        Objects.requireNonNull(key, "key");
        maybeSweep();
        // Acquire inside compute() so a concurrent sweep can't remove this key's bucket mid-use.
        // See known-issues/in-memory-keyed-sweep-race.md.
        RateLimitDecision[] decision = new RateLimitDecision[1];
        buckets.compute(key, (k, bucket) -> {
            if (bucket == null) {
                bucket = new TokenBucketRateLimiter(capacity, refillTokens, refillPeriod, nanoTimeSource);
            }
            decision[0] = bucket.decide(permits);
            return bucket;
        });
        return decision[0];
    }

    /** Number of buckets currently held. */
    int size() {
        return buckets.size();
    }

    private void maybeSweep() {
        long last = lastSweepNanos.get();
        long now = nanoTimeSource.getAsLong();
        if (now - last < sweepIntervalNanos) {
            return;
        }
        // Only the thread that wins this update sweeps; the rest carry on.
        if (!lastSweepNanos.compareAndSet(last, now)) {
            return;
        }
        for (String key : buckets.keySet()) {
            buckets.computeIfPresent(key, (k, bucket) -> bucket.isFull() ? null : bucket);
        }
    }
}
