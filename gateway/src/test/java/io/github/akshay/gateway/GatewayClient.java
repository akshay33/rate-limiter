package io.github.akshay.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Sends test requests to the running gateway. */
final class GatewayClient {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final int port;

    GatewayClient(int port) {
        this.port = port;
    }

    HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).build());
    }

    /** As if nginx had forwarded the request for {@code forwardedFor}. */
    HttpResponse<String> get(String path, String forwardedFor) {
        return send(HttpRequest.newBuilder(uri(path)).header("X-Forwarded-For", forwardedFor).build());
    }

    /** Any method, with a body and extra headers. */
    HttpResponse<String> send(String method, String path, String body, java.util.Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return send(builder.build());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
