package io.github.akshay.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code demo.*} settings.
 *
 * @param showInstance add an {@code X-Served-By} header naming this gateway; demo only, since it's internal info
 * @param instanceId   name reported in {@code X-Served-By}
 */
@ConfigurationProperties("demo")
public record DemoProperties(
        @DefaultValue("false") boolean showInstance,
        @DefaultValue("gateway") String instanceId) {
}
