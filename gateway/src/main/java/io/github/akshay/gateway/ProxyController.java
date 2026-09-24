package io.github.akshay.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.Set;

/** Forwards requests that passed the rate limit to the backend and relays its response. */
@RestController
class ProxyController {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyController.class);

    /** Connection-level headers that apply to one hop only, plus ones the HTTP client sets itself. */
    private static final Set<String> NOT_FORWARDED = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "host", "content-length", "expect");

    private final RestClient backendClient;
    private final BackendProperties backend;

    ProxyController(RestClient backendClient, BackendProperties backend) {
        this.backendClient = backendClient;
        this.backend = backend;
    }

    @RequestMapping("/api/**")
    ResponseEntity<byte[]> forward(HttpServletRequest request) throws IOException {
        String query = request.getQueryString();
        URI target = URI.create(backend.url() + request.getRequestURI() + (query == null ? "" : "?" + query));
        byte[] body = request.getInputStream().readAllBytes();

        RestClient.RequestBodySpec call = backendClient
                .method(HttpMethod.valueOf(request.getMethod()))
                .uri(target)
                .headers(headers -> copyRequestHeaders(request, headers));
        if (body.length > 0) {
            call.body(body);
        }

        try {
            return call.exchange((req, res) -> ResponseEntity
                    .status(res.getStatusCode())
                    .headers(forwardableHeaders(res.getHeaders()))
                    .body(res.getBody().readAllBytes()));
        } catch (ResourceAccessException e) {
            return backendUnavailable(e);
        }
    }

    private static void copyRequestHeaders(HttpServletRequest request, HttpHeaders target) {
        for (String name : Collections.list(request.getHeaderNames())) {
            if (!NOT_FORWARDED.contains(name.toLowerCase())) {
                target.addAll(name, Collections.list(request.getHeaders(name)));
            }
        }
    }

    private static HttpHeaders forwardableHeaders(HttpHeaders source) {
        HttpHeaders result = new HttpHeaders();
        source.forEach((name, values) -> {
            if (!NOT_FORWARDED.contains(name.toLowerCase())) {
                result.addAll(name, values);
            }
        });
        return result;
    }

    /** Backend unreachable → 502 Bad Gateway; backend too slow to answer → 504 Gateway Timeout. */
    private static ResponseEntity<byte[]> backendUnavailable(ResourceAccessException e) {
        Throwable cause = e.getCause();
        boolean couldNotConnect = cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException;
        HttpStatus status = !couldNotConnect && cause instanceof HttpTimeoutException
                ? HttpStatus.GATEWAY_TIMEOUT
                : HttpStatus.BAD_GATEWAY;
        LOG.warn("Backend call failed with {}: {}", status.value(), e.getMessage());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(("{\"error\":\"" + status.getReasonPhrase() + "\"}").getBytes());
    }
}
