package io.github.akshay.ratelimiter;

/** Token bucket as per-key state; a full bucket is idle, since it behaves like a new one. */
final class TokenBucketState implements KeyState {

    private final TokenBucketRateLimiter bucket;

    TokenBucketState(TokenBucketRateLimiter bucket) {
        this.bucket = bucket;
    }

    @Override
    public RateLimitDecision decide(int permits) {
        return bucket.decide(permits);
    }

    @Override
    public boolean isIdle() {
        return bucket.isFull();
    }
}
