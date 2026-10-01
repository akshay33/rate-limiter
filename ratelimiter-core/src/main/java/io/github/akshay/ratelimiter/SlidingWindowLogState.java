package io.github.akshay.ratelimiter;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.LongSupplier;

/**
 * Sliding window log for one key: remembers when each allowed request happened and allows a new
 * one only if fewer than {@code limit} fall within the last {@code window}. Exact, but memory
 * grows with the limit (one timestamp per allowed request).
 */
final class SlidingWindowLogState implements KeyState {

    private final long limit;
    private final long windowNanos;
    private final LongSupplier nanoTimeSource;
    /** Timestamps of allowed requests in the window, oldest first. */
    private final ArrayDeque<Long> timestamps = new ArrayDeque<>();

    SlidingWindowLogState(long limit, Duration window, LongSupplier nanoTimeSource) {
        this.limit = limit;
        this.windowNanos = window.toNanos();
        this.nanoTimeSource = nanoTimeSource;
    }

    @Override
    public RateLimitDecision decide(int permits) {
        validatePermits(permits, limit);
        long now = nanoTimeSource.getAsLong();
        evictExpired(now);

        if (timestamps.size() + permits <= limit) {
            for (int i = 0; i < permits; i++) {
                timestamps.addLast(now);
            }
            return RateLimitDecision.allow();
        }
        // Enough room frees up when the k-th oldest entry leaves the window.
        long k = timestamps.size() + permits - limit;
        Iterator<Long> oldestFirst = timestamps.iterator();
        long freesUpAt = 0;
        for (long i = 0; i < k; i++) {
            freesUpAt = oldestFirst.next() + windowNanos;
        }
        return RateLimitDecision.reject(Duration.ofNanos(freesUpAt - now));
    }

    @Override
    public boolean isIdle() {
        evictExpired(nanoTimeSource.getAsLong());
        return timestamps.isEmpty();
    }

    /** An entry leaves the window once a full window has passed since it was added. */
    private void evictExpired(long now) {
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowNanos) {
            timestamps.removeFirst();
        }
    }

    static void validatePermits(int permits, long limit) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive");
        }
        if (permits > limit) {
            throw new IllegalArgumentException("permits must not exceed limit (" + limit + ")");
        }
    }
}
