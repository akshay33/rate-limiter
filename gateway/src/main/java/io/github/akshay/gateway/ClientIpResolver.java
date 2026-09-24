package io.github.akshay.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Works out which client a request comes from, which is the rate-limit key. */
@Component
class ClientIpResolver {

    private final boolean trustForwardedFor;

    ClientIpResolver(RateLimitProperties props) {
        this.trustForwardedFor = props.trustForwardedFor();
    }

    String resolve(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                // "client, proxy1, proxy2": entries on the left can be forged by the client, so use the
                // last one, which was added by the proxy directly in front of us.
                String[] hops = forwardedFor.split(",");
                String last = hops[hops.length - 1].strip();
                if (!last.isEmpty()) {
                    return last;
                }
            }
        }
        // Not behind a trusted proxy (or no header): the TCP peer is the only thing we can trust.
        return request.getRemoteAddr();
    }
}
