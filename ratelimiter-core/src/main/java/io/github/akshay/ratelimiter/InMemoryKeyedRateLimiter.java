package io.github.akshay.ratelimiter;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * In-memory {@link KeyedRateLimiter} with separate state per key (a token bucket or a sliding
 * window, see {@link RateLimiters#inMemory(RateLimitConfig)}).
 *
 * <p>State lives in this process only, so each server in a cluster enforces its own limit.
 * Idle state (a full bucket, an empty window) is removed during periodic sweeps, which run on
 * the calling thread (no background thread).
 */
public final class InMemoryKeyedRateLimiter implements KeyedRateLimiter {

    static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofSeconds(30);

    private final Supplier<KeyState> newState;
    private final LongSupplier nanoTimeSource;
    private final long sweepIntervalNanos;

    private final ConcurrentHashMap<String, KeyState> states = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepNanos;

    /**
     * Token bucket per key.
     *
     * @param capacity     max tokens in each key's bucket
     * @param refillTokens tokens added per period
     * @param refillPeriod how often tokens are added
     */
    public InMemoryKeyedRateLimiter(long capacity, long refillTokens, Duration refillPeriod) {
        this(capacity, refillTokens, refillPeriod, System::nanoTime, DEFAULT_SWEEP_INTERVAL);
    }

    /** Token bucket per key, with a custom time source and sweep interval so tests can control both. */
    InMemoryKeyedRateLimiter(long capacity, long refillTokens, Duration refillPeriod,
                             LongSupplier nanoTimeSource, Duration sweepInterval) {
        this(RateLimitConfig.tokenBucket(capacity, refillTokens, refillPeriod), nanoTimeSource, sweepInterval);
    }

    /** Any algorithm, with a custom time source and sweep interval. */
    InMemoryKeyedRateLimiter(RateLimitConfig config, LongSupplier nanoTimeSource, Duration sweepInterval) {
        Objects.requireNonNull(config, "config");
        if (sweepInterval.isNegative()) {
            throw new IllegalArgumentException("sweepInterval must not be negative");
        }
        this.newState = switch (config) {
            case RateLimitConfig.TokenBucket c -> () -> new TokenBucketState(new TokenBucketRateLimiter(
                    c.capacity(), c.refillTokens(), c.refillPeriod(), nanoTimeSource));
            case RateLimitConfig.SlidingWindowLog c -> () -> new SlidingWindowLogState(c.limit(), c.window(), nanoTimeSource);
            case RateLimitConfig.SlidingWindowCounter c -> () -> new SlidingWindowCounterState(c.limit(), c.window(), nanoTimeSource);
        };
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
        // Acquire inside compute() so a concurrent sweep can't remove this key's state mid-use.
        // See known-issues/in-memory-keyed-sweep-race.md.
        RateLimitDecision[] decision = new RateLimitDecision[1];
        states.compute(key, (k, state) -> {
            if (state == null) {
                state = newState.get();
            }
            decision[0] = state.decide(permits);
            return state;
        });
        return decision[0];
    }

    /** Number of keys currently tracked. */
    int size() {
        return states.size();
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
        for (String key : states.keySet()) {
            states.computeIfPresent(key, (k, state) -> state.isIdle() ? null : state);
        }
    }
}
