package io.github.akshay.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Two real gateway instances sharing one Redis enforce one combined limit per client (5 in total). */
@Testcontainers
class GatewaysShareRedisTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final StubBackend BACKEND = new StubBackend();
    private static ConfigurableApplicationContext gateway1;
    private static ConfigurableApplicationContext gateway2;

    @BeforeAll
    static void startGateways() {
        gateway1 = startGateway("gw1");
        gateway2 = startGateway("gw2");
    }

    @AfterAll
    static void stopGateways() {
        gateway1.close();
        gateway2.close();
        BACKEND.close();
    }

    @Test
    void limitIsSharedAcrossGateways() {
        GatewayClient[] gateways = {client(gateway1), client(gateway2)};

        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            // Alternate between gateways, like a load balancer would.
            if (gateways[i % 2].get("/api/hello", "10.5.0.1").statusCode() == 200) {
                allowed++;
            }
        }

        assertEquals(5, allowed, "one shared limit of 5, not 5 per gateway");
        assertEquals(5, BACKEND.requestCount());
    }

    private static ConfigurableApplicationContext startGateway(String id) {
        return new SpringApplicationBuilder(GatewayApplication.class).run(
                "--server.port=0",
                "--ratelimit.mode=redis",
                "--ratelimit.trust-forwarded-for=true",
                "--ratelimit.redis.url=redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379),
                "--backend.url=" + BACKEND.url(),
                "--demo.instance-id=" + id);
    }

    private static GatewayClient client(ConfigurableApplicationContext context) {
        return new GatewayClient(Integer.parseInt(context.getEnvironment().getProperty("local.server.port")));
    }
}
