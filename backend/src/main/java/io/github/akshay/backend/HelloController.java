package io.github.akshay.backend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The resource the gateway protects. It has no idea it's being rate limited. */
@RestController
public class HelloController {

    record HelloResponse(String message) { }

    @GetMapping("/api/hello")
    public HelloResponse hello() {
        return new HelloResponse("Hello from the backend");
    }
}
