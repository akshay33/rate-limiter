package io.github.akshay.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code backend.*} settings: where allowed requests are forwarded.
 *
 * @param url            base URL of the backend service
 * @param connectTimeout max time to open a connection (then 502)
 * @param readTimeout    max time to wait for the response (then 504)
 */
@ConfigurationProperties("backend")
public record BackendProperties(
        @DefaultValue("http://localhost:8081") String url,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {
}
