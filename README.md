# rate-limiter

A thread-safe token bucket rate limiter for Java, and a distributed
rate-limiting gateway built on it: several gateway instances share one limit
per client IP through Redis.

## Quickstart: run the cluster

Requires Docker.

```bash
docker compose up --build
```

Then open **http://localhost:8080** and click **Send 20 at once**: 5 requests
are allowed and the rest get `429 Too Many Requests`, even though the requests
are spread across three gateway instances.

```
 browser ──► nginx :8080 ──► gw1 ┐
               (round-robin) gw2 ├──► backend
                             gw3 ┘
                              │
                            Redis   (shared token buckets, one per client IP)
```

Only nginx is reachable from outside; the gateways, backend and Redis are on
Docker's internal network. If Redis goes down, each gateway falls back to its
own in-memory limit (so the site stays up and still limited), and switches back
when Redis returns. Stop everything with `docker compose down`.

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

**Requirements:** Java 21+ and a running Docker (the Redis module's tests start
a real Redis with [Testcontainers](https://testcontainers.com)). Maven doesn't
need to be installed; the included Maven Wrapper (`./mvnw`) downloads the right
version on first run.

```bash
git clone https://github.com/<your-username>/rate-limiter.git
cd rate-limiter

./mvnw test       # compile and run the test suite
./mvnw package    # build the jars
```

The core library jar is written to
`ratelimiter-core/target/ratelimiter-core-1.0.0-SNAPSHOT.jar`.

## Project layout

| Module | Contents |
|---|---|
| `ratelimiter-core` | `RateLimiter` and the in-memory `TokenBucketRateLimiter`; `KeyedRateLimiter` and `InMemoryKeyedRateLimiter` for one bucket per key (e.g. per client IP); `FallbackKeyedRateLimiter` switches to a fallback limiter while the primary (e.g. Redis) is unavailable. No external dependencies. |
| `ratelimiter-redis` | `RedisTokenBucketRateLimiter`: buckets live in Redis, so all application instances share one limit per key. Refill and deduct run atomically in a Lua script using Redis's clock. Depends on Lettuce. |
| `backend` | Spring Boot demo service (`GET /api/hello`, `/actuator/health`) that sits behind the gateway. Knows nothing about rate limiting. |
| `gateway` | Spring Boot rate-limiting gateway. Limits `/api/**` by client IP (429 + `Retry-After` when over the limit) and forwards allowed requests to the backend. Uses Redis for one shared limit across gateway instances, falling back to in-memory limits while Redis is unavailable. |

Run it locally (Redis optional: without it, the gateway uses in-memory limits):

```bash
./mvnw package -DskipTests
docker run -d -p 6379:6379 redis:7-alpine              # optional
java -jar backend/target/backend-1.0.0-SNAPSHOT.jar    # :8081
java -jar gateway/target/gateway-1.0.0-SNAPSHOT.jar    # :8080
curl -i localhost:8080/api/hello                       # 5 quick requests, then 429
```

Gateway settings live in `gateway/src/main/resources/application.yml` and can be
overridden with environment variables (e.g. `RATELIMIT_MODE=memory`,
`RATELIMIT_CAPACITY=10`, `BACKEND_URL=http://backend:8081`).

## Scope

This is a single-process, in-memory rate limiter — a deliberately small
first pass. Natural follow-ups: additional algorithms (sliding window,
leaky bucket) for comparison, and a distributed mode backed by Redis for
multi-instance deployments.
