package io.github.akshay.ratelimiter;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Sliding window counter for one key: counts requests in the current and previous fixed window
 * and estimates the last {@code window} as
 * {@code current + previous × (share of the previous window still inside the sliding window)}.
 * Constant memory; assumes requests in the previous window were spread evenly.
 */
final class SlidingWindowCounterState implements KeyState {

    private final long limit;
    private final long windowNanos;
    private final LongSupplier nanoTimeSource;

    private long windowIndex;
    private long current;
    private long previous;

    SlidingWindowCounterState(long limit, Duration window, LongSupplier nanoTimeSource) {
        this.limit = limit;
        this.windowNanos = window.toNanos();
        this.nanoTimeSource = nanoTimeSource;
        this.windowIndex = Math.floorDiv(nanoTimeSource.getAsLong(), windowNanos);
    }

    @Override
    public RateLimitDecision decide(int permits) {
        SlidingWindowLogState.validatePermits(permits, limit);
        long now = nanoTimeSource.getAsLong();
        roll(now);

        double elapsed = now - windowIndex * windowNanos;
        double previousWeight = 1.0 - elapsed / windowNanos;
        double estimate = current + previous * previousWeight;
        if (estimate + permits <= limit) {
            current += permits;
            return RateLimitDecision.allow();
        }
        return RateLimitDecision.reject(Duration.ofNanos((long) Math.ceil(waitNanos(permits, elapsed))));
    }

    @Override
    public boolean isIdle() {
        roll(nanoTimeSource.getAsLong());
        return current == 0 && previous == 0;
    }

    /** Moves to the window containing {@code now}; counts older than one window back are dropped. */
    private void roll(long now) {
        long index = Math.floorDiv(now, windowNanos);
        if (index == windowIndex) {
            return;
        }
        previous = index == windowIndex + 1 ? current : 0;
        current = 0;
        windowIndex = index;
    }

    /**
     * Time until {@code estimate + permits <= limit}, from the estimate formula. The previous
     * window's weight shrinks linearly; if that isn't enough, wait into the next window, where the
     * current count becomes the "previous" one and starts shrinking.
     */
    private double waitNanos(int permits, double elapsed) {
        double room = limit - permits; // estimate must drop to this
        if (current <= room) {
            // Within this window: previous × (1 - t/W) <= room - current
            double t = windowNanos * (1.0 - (room - current) / previous);
            return Math.max(t - elapsed, 1);
        }
        // Next window: current × (1 - t'/W) <= room
        double tNext = windowNanos * (1.0 - room / current);
        return (windowNanos - elapsed) + tNext;
    }
}
