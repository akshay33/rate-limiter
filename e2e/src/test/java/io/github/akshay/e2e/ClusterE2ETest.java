package io.github.akshay.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole system through nginx, the way users reach it: nginx → 3 gateways → backend, Redis.
 *
 * <p>Uses the demo limit from the gateway's application.yml: 5 requests, then 1 more every 2 s.
 * Under continuous load for T seconds, one bucket allows about {@code 5 + T / 2} requests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClusterE2ETest {

    private static final int RATE = 20;                          // requests per second
    private static final Duration LOAD = Duration.ofSeconds(10);
    /** One bucket over the load window: 5 up front + 1 per 2 s. */
    private static final int ONE_BUCKET = 5 + (int) (LOAD.toSeconds() / 2);

    private static Cluster redisCluster;
    private static final List<String> resultRows = new ArrayList<>();
    private static LoadGenerator.Result redisResult;

    @BeforeAll
    static void startCluster() {
        redisCluster = Cluster.start("redis");
    }

    @AfterAll
    static void stopClusterAndWriteResults() throws IOException {
        if (redisCluster != null) {
            redisCluster.close();
        }
        writeResults();
    }

    @Test
    @Order(1)
    void redisModeEnforcesOneLimitAcrossAllGateways() throws Exception {
        redisCluster.resetRedis();

        LoadGenerator.Result result = load(redisCluster);
        redisResult = result;
        record("redis (shared)", result);

        assertEquals(0, result.other(), "every request should get 200 or 429");
        assertTrue(result.allowed() >= ONE_BUCKET - 2 && result.allowed() <= ONE_BUCKET + 3,
                "expected about " + ONE_BUCKET + " allowed (one shared bucket), got " + result.allowed());
        // nginx spreads requests evenly, so the shared limit really is shared by all three.
        assertEquals(3, result.servedBy().size(), "served by " + result.servedBy());
        for (int count : result.servedBy().values()) {
            double share = (double) count / result.sent();
            assertTrue(share > 0.25 && share < 0.42, "uneven load balancing: " + result.servedBy());
        }
    }

    @Test
    @Order(2)
    void onlyNginxIsReachableFromOutside() {
        for (String service : Cluster.INTERNAL_SERVICES) {
            Map<?, ?> bindings = redisCluster.service(service).getContainerInfo()
                    .getHostConfig().getPortBindings().getBindings();
            assertTrue(bindings.isEmpty(), service + " must not publish ports, but has " + bindings);
        }
        assertTrue(!redisCluster.service("nginx").getContainerInfo()
                .getHostConfig().getPortBindings().getBindings().isEmpty(), "nginx is the entry point");
    }

    @Test
    @Order(3)
    void redisOutageFallsBackToPerGatewayLimitsThenRecovers() throws Exception {
        Duration shortLoad = Duration.ofSeconds(4);
        int oneBucketShort = 5 + (int) (shortLoad.toSeconds() / 2);

        redisCluster.stopService("redis");
        LoadGenerator.Result duringOutage = LoadGenerator.run(apiUrl(redisCluster), RATE, shortLoad);
        record("redis stopped (fallback)", duringOutage);
        assertEquals(0, duringOutage.other(), "the site must stay up while Redis is down");
        assertTrue(duringOutage.allowed() > oneBucketShort + 3,
                "each gateway limits on its own during the outage, so more than one bucket's worth gets through: "
                        + duringOutage.allowed());
        assertTrue(duringOutage.allowed() <= 3 * oneBucketShort + 3, "still limited, not wide open");

        redisCluster.startService("redis");
        Thread.sleep(10_000); // gateways reconnect, and per-gateway buckets refill
        redisCluster.resetRedis();

        LoadGenerator.Result recovered = LoadGenerator.run(apiUrl(redisCluster), RATE, shortLoad);
        record("redis back", recovered);
        assertEquals(0, recovered.other());
        assertTrue(recovered.allowed() <= oneBucketShort + 3,
                "back to one shared limit after Redis returns, got " + recovered.allowed());
    }

    @Test
    @Order(4)
    void inMemoryModeLetsEachGatewayLimitSeparately() throws Exception {
        try (Cluster memoryCluster = Cluster.start("memory")) {
            LoadGenerator.Result result = load(memoryCluster);
            record("memory (per gateway)", result);

            assertEquals(0, result.other());
            assertTrue(result.allowed() >= 3 * ONE_BUCKET - 6 && result.allowed() <= 3 * ONE_BUCKET + 6,
                    "expected about " + 3 * ONE_BUCKET + " allowed (3 separate buckets), got " + result.allowed());
            if (redisResult != null) {
                assertTrue(result.allowed() >= 2.5 * redisResult.allowed(),
                        "in-memory should allow about 3x what Redis allows: " + result.allowed()
                                + " vs " + redisResult.allowed());
            }
        }
    }

    private static LoadGenerator.Result load(Cluster cluster) throws InterruptedException {
        return LoadGenerator.run(apiUrl(cluster), RATE, LOAD);
    }

    private static String apiUrl(Cluster cluster) {
        return cluster.baseUrl() + "/api/hello";
    }

    private static void record(String scenario, LoadGenerator.Result r) {
        String row = String.format("| %s | %d | %d | %d | %.1f | %s |",
                scenario, r.sent(), r.allowed(), r.limited(), r.allowedPerSecond(), r.servedBy());
        resultRows.add(row);
        System.out.println("[load] " + row);
    }

    private static void writeResults() throws IOException {
        String table = String.join("\n",
                "Limit: 5 requests, then 1 every 2 s (per client IP). Load: " + RATE
                        + " requests/s from one client through nginx.",
                "",
                "| Scenario | Sent | Allowed (200) | Limited (429) | Allowed/s | Served by |",
                "|---|---|---|---|---|---|",
                String.join("\n", resultRows),
                "");
        Path out = Path.of("target", "load-test-results.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table);
        System.out.println(table);
    }
}
