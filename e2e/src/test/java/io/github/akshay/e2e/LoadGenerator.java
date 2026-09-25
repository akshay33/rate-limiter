package io.github.akshay.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends requests at a steady rate (open loop: the next request doesn't wait for the previous
 * response), like independent clicks arriving over time, and tallies the results.
 */
final class LoadGenerator {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    record Result(int sent, int allowed, int limited, int other, Map<String, Integer> servedBy, Duration duration) {
        double allowedPerSecond() {
            return allowed / (duration.toMillis() / 1000.0);
        }
    }

    static Result run(String url, int requestsPerSecond, Duration duration) throws InterruptedException {
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        Map<String, AtomicInteger> servedBy = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> inFlight = new ArrayList<>();

        long intervalNanos = 1_000_000_000L / requestsPerSecond;
        int total = (int) (requestsPerSecond * duration.toMillis() / 1000);
        long start = System.nanoTime();
        for (int i = 0; i < total; i++) {
            long sendAt = start + i * intervalNanos;
            long waitNanos = sendAt - System.nanoTime();
            if (waitNanos > 0) {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build();
            inFlight.add(HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).handle((response, error) -> {
                if (error != null) {
                    other.incrementAndGet();
                    return null;
                }
                switch (response.statusCode()) {
                    case 200 -> allowed.incrementAndGet();
                    case 429 -> limited.incrementAndGet();
                    default -> other.incrementAndGet();
                }
                response.headers().firstValue("X-Served-By")
                        .ifPresent(gw -> servedBy.computeIfAbsent(gw, k -> new AtomicInteger()).incrementAndGet());
                return null;
            }));
        }
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        Map<String, Integer> byGateway = new TreeMap<>();
        servedBy.forEach((gw, count) -> byGateway.put(gw, count.get()));
        return new Result(total, allowed.get(), limited.get(), other.get(), byGateway, elapsed);
    }
}
