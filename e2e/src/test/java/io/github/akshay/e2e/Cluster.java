package io.github.akshay.e2e;

import com.github.dockerjava.api.DockerClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.time.Duration;

/** The real cluster from the repo's docker-compose.yml, started with a given rate-limit mode. */
final class Cluster implements AutoCloseable {

    static final String[] INTERNAL_SERVICES = {"redis", "backend", "gw1", "gw2", "gw3"};

    private final ComposeContainer compose;

    private Cluster(String mode) {
        compose = new ComposeContainer(new File("../docker-compose.yml"))
                .withEnv("RATELIMIT_MODE", mode)
                .withEnv("NGINX_PORT", "0") // random host port, so a running local cluster doesn't clash
                .withBuild(true)
                .withExposedService("nginx", 80,
                        Wait.forHttp("/actuator/health").forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(10)));
    }

    static Cluster start(String mode) {
        Cluster cluster = new Cluster(mode);
        cluster.compose.start();
        return cluster;
    }

    /** Base URL of nginx, the only entry point. */
    String baseUrl() {
        return "http://" + compose.getServiceHost("nginx", 80) + ":" + compose.getServicePort("nginx", 80);
    }

    ContainerState service(String name) {
        return compose.getContainerByServiceName(name)
                .orElseThrow(() -> new IllegalStateException("no container for service " + name));
    }

    /** Clears all rate-limit buckets so a test starts from full limits. */
    void resetRedis() throws Exception {
        service("redis").execInContainer("redis-cli", "FLUSHALL");
    }

    void stopService(String name) {
        docker().stopContainerCmd(service(name).getContainerId()).exec();
    }

    void startService(String name) {
        docker().startContainerCmd(service(name).getContainerId()).exec();
    }

    private static DockerClient docker() {
        return DockerClientFactory.instance().client();
    }

    @Override
    public void close() {
        compose.stop();
    }
}
