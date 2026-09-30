# rate-limiter

[![CI](https://github.com/akshay33/rate-limiter/actions/workflows/ci.yml/badge.svg)](https://github.com/akshay33/rate-limiter/actions/workflows/ci.yml)

A rate-limiting gateway in Java that enforces one limit per client across
several gateway instances, using a token bucket stored in Redis.

The problem it solves: if each server keeps its own counter, three servers let a
client through at three times the limit. Here the buckets live in Redis, so the
limit holds no matter which instance a request lands on.

## Try it

Needs Docker.

```bash
docker compose up --build
```

Open http://localhost:8080 and click "Send 20 at once". Five requests get
through and the rest get `429 Too Many Requests`, even though nginx spreads them
across three gateways.

```
browser ──► nginx :8080 ──► gw1 ┐
              (round-robin) gw2 ├──► backend
                            gw3 ┘
                             │
                           Redis   (one token bucket per client IP)
```

Only nginx is exposed; the gateways, backend and Redis sit on Docker's internal
network. `docker compose down` stops everything.

## How a request is handled

1. nginx sets `X-Forwarded-For` to the client's address, overwriting whatever the
   client sent.
2. The gateway's filter takes the client IP and asks the limiter for a token.
3. The limiter runs a Lua script in Redis that refills the bucket for the time
   elapsed and takes a token, in one atomic step.
4. No token: the gateway returns 429 with `Retry-After`. Otherwise it forwards the
   request to the backend and relays the response (502/504 if the backend is down
   or slow).

## Design decisions

- **Atomic Lua script.** Refill and deduct happen inside Redis in one script.
  Doing a read and a write from Java lets two servers spend the same token; the
  concurrency test proves this (3,000 allowed instead of 100 with a naive
  read-then-write).
- **Redis's clock.** The script uses `TIME` instead of each server's clock, so
  clock drift between servers can't skew refills.
- **Expiring keys.** A bucket expires once it would have refilled completely. A
  full bucket behaves exactly like a missing one, so nothing is lost and Redis
  cleans up idle clients on its own.
- **Fallback when Redis is down.** Redis calls time out after 100 ms and each
  gateway switches to its own in-memory limiter, then back when Redis returns.
  The site stays up and still limited, just more loosely. Gateways also start
  if Redis isn't reachable yet.
- **Client IP.** `X-Forwarded-For` is only trusted behind a proxy (a config
  switch), and only its last entry, which the proxy added. Otherwise the
  connection's address is used, so a forged header doesn't buy a fresh limit.
- **What clients see.** Only 200 or 429 with `Retry-After`. Token counts stay
  internal.
- **Plain Java core.** The limiter modules don't depend on Spring; Spring Boot
  only hosts the gateway and backend. Spring Cloud Gateway already includes a
  Redis rate limiter built the same way. I wrote my own to understand and test
  that part rather than configure it.

## Results

The end-to-end test starts the real cluster and sends 20 requests/s for 10 s
from one client. With a limit of 5 plus one every 2 s, one bucket should allow
about 10.

| Mode | Sent | Allowed | Rate limited | Split across gw1/gw2/gw3 |
|---|---|---|---|---|
| Redis (shared bucket) | 200 | 9 | 191 | 66 / 67 / 67 |
| In-memory (bucket per gateway) | 200 | 27 | 173 | 66 / 67 / 67 |

In-memory limits let the client through at 3× the intended rate; Redis holds it.
Stopping Redis mid-test, the gateways fell back to their own limits (18 of 80
allowed, no errors), and went back to the shared limit (6 of 80) once it
returned. CI on GitHub's runners gives the same numbers.

## Tests

70 tests run on every build, plus 4 end-to-end tests against the Docker Compose
cluster. The Redis tests use a real Redis through Testcontainers.

The concurrency tests were checked against deliberately broken versions to make
sure they'd catch the bug they target: a race between bucket cleanup and
acquire, a non-atomic Redis update, and a missing timeout. Writing these also
turned up a real bug: an `X-Forwarded-For` value with a trailing comma made the
gateway trust the client-supplied address.

```bash
./mvnw verify                 # all modules and tests (needs Docker running)
./mvnw -Pe2e -pl e2e verify   # cluster and load test, a few minutes
```

Maven isn't required; `./mvnw` downloads it. Java 21+.

## Modules

| Module | What's in it |
|---|---|
| `ratelimiter-core` | Token bucket, per-key limiter with idle-bucket cleanup, fallback wrapper. No dependencies. |
| `ratelimiter-redis` | Redis-backed per-key limiter (Lua script, Lettuce client). |
| `gateway` | Spring Boot gateway: rate-limit filter, client IP handling, proxy to the backend, demo page. |
| `backend` | Spring Boot demo service behind the gateway. No rate-limiting code. |
| `e2e` | End-to-end and load tests against the compose cluster (built with `-Pe2e`). |

Using the library directly:

```java
KeyedRateLimiter limiter = new InMemoryKeyedRateLimiter(5, 1, Duration.ofSeconds(2));

RateLimitDecision decision = limiter.tryAcquire(clientIp);
if (!decision.allowed()) {
    // reject; decision.retryAfter() says how long until a token is free
}
```

The gateway is configured in `gateway/src/main/resources/application.yml`; any
setting can be overridden with an environment variable, e.g.
`RATELIMIT_MODE=memory`, `RATELIMIT_CAPACITY=10`, `BACKEND_URL=...`.

## Known limitations

- During a Redis outage the limit loosens to one bucket per gateway, and clients
  start with a full bucket when the fallback kicks in.
- Any Redis error triggers the fallback, including a bug in the Lua script, which
  would only show up as a log warning.
- If Redis hangs rather than going down, each request waits the full 100 ms
  timeout. A circuit breaker would fix that.
- Clients are keyed by full IP address. An IPv6 user can rotate through their /64
  block to get fresh limits; keying IPv6 by /64 prefix would close that.
- A single Redis instance, with no replication or failover.
