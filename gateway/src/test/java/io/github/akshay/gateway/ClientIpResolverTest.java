package io.github.akshay.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which client a request is counted against, for every shape of X-Forwarded-For. */
class ClientIpResolverTest {

    private static final String PEER = "172.18.0.5"; // the TCP connection's address (nginx, when behind it)

    private final ClientIpResolver trusting = resolver(true);
    private final ClientIpResolver notTrusting = resolver(false);

    @Test
    void missingHeaderUsesConnectionAddress() {
        assertEquals(PEER, trusting.resolve(request(null)));
    }

    @Test
    void blankHeaderUsesConnectionAddress() {
        assertEquals(PEER, trusting.resolve(request("   ")));
    }

    @Test
    void singleEntryIsUsed() {
        assertEquals("203.0.113.7", trusting.resolve(request("203.0.113.7")));
    }

    @Test
    void severalEntriesUseTheLastOneWhichOurProxyAdded() {
        assertEquals("203.0.113.7", trusting.resolve(request("6.6.6.6, 10.0.0.1, 203.0.113.7")));
    }

    @Test
    void whitespaceAroundEntriesIsIgnored() {
        assertEquals("203.0.113.7", trusting.resolve(request("6.6.6.6 ,   203.0.113.7  ")));
    }

    @Test
    void trailingEmptyEntryFallsBackToConnectionAddress() {
        // "a.b.c.d," has nothing after the last comma: don't trust a forged left-hand entry instead.
        assertEquals(PEER, trusting.resolve(request("6.6.6.6,")));
    }

    @Test
    void ipv6AddressIsKeptAsIs() {
        assertEquals("2001:db8::1", trusting.resolve(request("2001:db8::1")));
    }

    @Test
    void malformedValueIsUsedVerbatimAsTheKey() {
        // Behind nginx this can't happen (nginx writes the header itself); documented behavior otherwise.
        assertEquals("not-an-ip", trusting.resolve(request("not-an-ip")));
    }

    @Test
    void headerIsIgnoredWhenNotTrusted() {
        assertEquals(PEER, notTrusting.resolve(request("203.0.113.7")));
    }

    private static MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/hello");
        request.setRemoteAddr(PEER);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private static ClientIpResolver resolver(boolean trustForwardedFor) {
        return new ClientIpResolver(new RateLimitProperties(
                RateLimitProperties.Mode.MEMORY, RateLimitProperties.Algorithm.TOKEN_BUCKET, 5, Duration.ofSeconds(10),
                1, Duration.ofSeconds(2), trustForwardedFor,
                new RateLimitProperties.Redis("redis://localhost:6379", Duration.ofMillis(100), Duration.ofSeconds(2))));
    }
}
