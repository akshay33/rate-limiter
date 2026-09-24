package io.github.akshay.gateway;

import io.github.akshay.ratelimiter.KeyedRateLimiter;
import io.github.akshay.ratelimiter.RateLimitDecision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs before every {@code /api/**} request: rejects it with 429 when the client is over its
 * limit, otherwise lets it through to the proxy. Other paths (demo page, health) aren't limited.
 */
@Component
class RateLimitFilter extends OncePerRequestFilter {

    static final String SERVED_BY_HEADER = "X-Served-By";

    private final KeyedRateLimiter limiter;
    private final ClientIpResolver clientIpResolver;
    private final DemoProperties demo;

    RateLimitFilter(KeyedRateLimiter limiter, ClientIpResolver clientIpResolver, DemoProperties demo) {
        this.limiter = limiter;
        this.clientIpResolver = clientIpResolver;
        this.demo = demo;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (demo.showInstance()) {
            response.setHeader(SERVED_BY_HEADER, demo.instanceId());
        }

        RateLimitDecision decision = limiter.tryAcquire(clientIpResolver.resolve(request));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        // Only whether to retry and when leave the gateway; token counts stay internal.
        long retryAfterSeconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Too Many Requests\"}");
    }
}
