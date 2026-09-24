package io.github.akshay.ratelimiter;

/** Thrown when a limiter can't reach its backing store (for example, Redis is down or too slow). */
public class RateLimiterUnavailableException extends RuntimeException {

    public RateLimiterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
