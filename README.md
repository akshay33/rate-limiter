# rate-limiter

A thread-safe token bucket rate limiter for Java.

## Usage

```java
import io.github.akshay.ratelimiter.RateLimiter;
import io.github.akshay.ratelimiter.TokenBucketRateLimiter;
import java.time.Duration;

// bucket holds up to 10 tokens, refilling 10 tokens every second
RateLimiter limiter = new TokenBucketRateLimiter(10, 10, Duration.ofSeconds(1));

if (limiter.tryAcquire()) {
    // proceed with the request
} else {
    // reject / throttle the caller
}

// acquire more than one permit at once
limiter.tryAcquire(5);
```

## Design

- **Lazy refill.** Tokens aren't added by a background thread on a timer;
  instead, each `tryAcquire()` call computes how much time has elapsed
  since the last refill and tops up the bucket accordingly (capped at
  `capacity`) before attempting to deduct. This avoids extra threads,
  timers, and the coordination they'd require.

- **Correctness under concurrency.** Refilling and deducting tokens is a
  single logical read-modify-write over two related fields (token count,
  last-refill timestamp), so it's guarded by a `ReentrantLock` rather than
  attempted lock-free. This keeps the implementation easy to verify as
  correct, which matters more here than shaving off a small amount of lock
  overhead. This is exercised directly by a test that hammers a limiter
  from thousands of threads concurrently and asserts the number of granted
  permits exactly matches capacity — no more (over-granting) and no fewer
  (lost updates).

- **Testable without real time.** The time source is injected as a
  `LongSupplier` (nanoseconds), defaulting to `System::nanoTime`. Tests
  use a fake, manually-advanced clock instead of sleeping, so refill
  behavior is tested deterministically and fast.

## Build & test

**Requirements:** Java 21+, Maven 3.6+

```bash
git clone https://github.com/<your-username>/rate-limiter.git
cd rate-limiter

mvn test       # compile and run the test suite
mvn package    # build the jar (target/rate-limiter-1.0.0-SNAPSHOT.jar)
```

## Scope

This is a single-process, in-memory rate limiter — a deliberately small
first pass. Natural follow-ups: additional algorithms (sliding window,
leaky bucket) for comparison, and a distributed mode backed by Redis for
multi-instance deployments.
