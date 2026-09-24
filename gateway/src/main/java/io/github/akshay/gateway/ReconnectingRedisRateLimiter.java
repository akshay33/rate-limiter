package io.github.akshay.gateway;

import io.github.akshay.ratelimiter.KeyedRateLimiter;
import io.github.akshay.ratelimiter.RateLimitDecision;
import io.github.akshay.ratelimiter.RateLimiterUnavailableException;
import io.github.akshay.ratelimiter.redis.RedisTokenBucketRateLimiter;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redis limiter that lets the gateway start even when Redis is down.
 *
 * <p>Until a first connection succeeds, every call throws {@link RateLimiterUnavailableException}
 * (so {@code FallbackKeyedRateLimiter} answers from memory) while a background thread keeps
 * retrying. Once connected, Lettuce's own auto-reconnect handles later drops.
 */
final class ReconnectingRedisRateLimiter implements KeyedRateLimiter, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ReconnectingRedisRateLimiter.class);

    private final RedisClient client;
    private final RateLimitProperties props;
    private final ScheduledExecutorService reconnector;

    private volatile StatefulRedisConnection<String, String> connection;
    private volatile RedisTokenBucketRateLimiter delegate;
    /** Only touched by the constructor and then the single reconnect thread. */
    private boolean warnedUnreachable;

    ReconnectingRedisRateLimiter(RateLimitProperties props) {
        this.props = props;
        RedisURI uri = RedisURI.create(props.redis().url());
        uri.setTimeout(props.redis().timeout());
        this.client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                // Fail immediately while disconnected instead of queueing until the timeout.
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
                .build());

        this.reconnector = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "redis-reconnect");
            t.setDaemon(true);
            return t;
        });
        if (!tryConnect()) {
            long intervalMs = props.redis().reconnectInterval().toMillis();
            reconnector.scheduleWithFixedDelay(this::retryConnect, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public RateLimitDecision tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int permits) {
        RedisTokenBucketRateLimiter current = delegate;
        if (current == null) {
            throw new RateLimiterUnavailableException("Redis not connected yet", null);
        }
        return current.tryAcquire(key, permits);
    }

    boolean isConnected() {
        return delegate != null;
    }

    private void retryConnect() {
        if (tryConnect()) {
            reconnector.shutdown(); // connected: Lettuce handles reconnects from here
        }
    }

    private boolean tryConnect() {
        try {
            connection = client.connect();
            delegate = new RedisTokenBucketRateLimiter(
                    connection, props.capacity(), props.refillTokens(), props.refillPeriod());
            LOG.info("Connected to Redis at {}", props.redis().url());
            return true;
        } catch (RuntimeException e) {
            // Warn once, not on every retry.
            if (!warnedUnreachable) {
                warnedUnreachable = true;
                LOG.warn("Redis not reachable at {} ({}); using in-memory fallback, retrying every {}",
                        props.redis().url(), e.getMessage(), props.redis().reconnectInterval());
            } else {
                LOG.debug("Redis still not reachable: {}", e.getMessage());
            }
            return false;
        }
    }

    @Override
    public void close() {
        reconnector.shutdownNow();
        if (connection != null) {
            connection.close();
        }
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
}
