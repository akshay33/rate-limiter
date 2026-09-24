package io.github.akshay.gateway;

import io.github.akshay.ratelimiter.FallbackKeyedRateLimiter;
import io.github.akshay.ratelimiter.InMemoryKeyedRateLimiter;
import io.github.akshay.ratelimiter.KeyedRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
class RateLimiterConfig {

    /** {@code ratelimit.mode=memory}: each gateway enforces its own limit. */
    @Bean
    @ConditionalOnProperty(name = "ratelimit.mode", havingValue = "memory")
    KeyedRateLimiter inMemoryRateLimiter(RateLimitProperties props) {
        return inMemory(props);
    }

    /** {@code ratelimit.mode=redis} (default): the shared Redis limiter. Spring closes it on shutdown. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "ratelimit.mode", havingValue = "redis", matchIfMissing = true)
    ReconnectingRedisRateLimiter redisRateLimiter(RateLimitProperties props) {
        return new ReconnectingRedisRateLimiter(props);
    }

    /** In redis mode, the limiter the filter uses: Redis first, in-memory while Redis is unavailable. */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "ratelimit.mode", havingValue = "redis", matchIfMissing = true)
    KeyedRateLimiter redisWithFallbackRateLimiter(ReconnectingRedisRateLimiter redis, RateLimitProperties props) {
        return new FallbackKeyedRateLimiter(redis, inMemory(props));
    }

    @Bean
    RestClient backendClient(BackendProperties backend) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(backend.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(backend.readTimeout());
        return RestClient.builder()
                .baseUrl(backend.url())
                .requestFactory(requestFactory)
                .build();
    }

    private static KeyedRateLimiter inMemory(RateLimitProperties props) {
        return new InMemoryKeyedRateLimiter(props.capacity(), props.refillTokens(), props.refillPeriod());
    }
}
