package com.muse.payments.shared.infrastructure.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Accepts either an OTEL base URL or a full traces URL, and turns export off when no URL is set.
 * {@code OTEL_EXPORTER_OTLP_ENDPOINT} is a base URL in the OTEL spec. Spring Boot posts to
 * {@code management.otlp.tracing.endpoint} exactly as given, so a base URL otherwise 404s.
 * {@code /v1/traces} is appended only for HTTP, the default transport. gRPC keeps the base URL.
 * Spring Boot 3.5 loads this class from {@code META-INF/spring.factories}. The
 * {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor} imports file is not read.
 */
public final class OtlpEndpointNormalizer implements EnvironmentPostProcessor, Ordered {

    static final String ENDPOINT = "management.otlp.tracing.endpoint";
    static final String EXPORT_ENABLED = "management.otlp.tracing.export.enabled";
    static final String TRANSPORT = "management.otlp.tracing.transport";
    static final String NORMALIZED_SOURCE = "otlpEndpointNormalized";

    private static final Logger log = LoggerFactory.getLogger(OtlpEndpointNormalizer.class);
    private static final String TRACES_PATH = "/v1/traces";

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        apply(environment);
    }

    static void apply(ConfigurableEnvironment environment) {
        String configured = environment.getProperty(ENDPOINT);
        Map<String, Object> overrides = new HashMap<>();
        if (configured == null || configured.isBlank()) {
            overrides.put(EXPORT_ENABLED, "false");
            log.info("OTLP trace export is disabled because no collector endpoint is configured");
        } else if (httpTransport(environment)) {
            String normalized = tracesEndpoint(configured);
            if (!normalized.equals(configured)) {
                overrides.put(ENDPOINT, normalized);
            }
        }
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(NORMALIZED_SOURCE, overrides));
        }
    }

    private static boolean httpTransport(ConfigurableEnvironment environment) {
        String transport = environment.getProperty(TRANSPORT);
        return transport == null || transport.isBlank() || "http".equalsIgnoreCase(transport.trim());
    }

    static String tracesEndpoint(String endpoint) {
        String trimmed = endpoint.trim().replaceAll("/+$", "");
        if (trimmed.endsWith(TRACES_PATH)) {
            return trimmed;
        }
        return trimmed + TRACES_PATH;
    }
}
