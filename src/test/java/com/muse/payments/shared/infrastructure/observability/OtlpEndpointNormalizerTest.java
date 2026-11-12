package com.muse.payments.shared.infrastructure.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpEndpointNormalizerTest {

    @Test
    @DisplayName("A collector base URL gains the traces path Spring Boot posts to")
    void appendsTracesPathToBaseUrl() {
        assertThat(OtlpEndpointNormalizer.tracesEndpoint("http://otel-collector:4318"))
                .isEqualTo("http://otel-collector:4318/v1/traces");
        assertThat(OtlpEndpointNormalizer.tracesEndpoint("http://otel-collector:4318/"))
                .isEqualTo("http://otel-collector:4318/v1/traces");
    }

    @Test
    @DisplayName("A full traces URL is kept")
    void keepsFullTracesUrl() {
        assertThat(OtlpEndpointNormalizer.tracesEndpoint("http://localhost:4318/v1/traces"))
                .isEqualTo("http://localhost:4318/v1/traces");
        assertThat(OtlpEndpointNormalizer.tracesEndpoint("http://localhost:4318/v1/traces/"))
                .isEqualTo("http://localhost:4318/v1/traces");
    }

    @Test
    @DisplayName("The environment stores the normalized traces URL")
    void rewritesBaseUrlOnTheEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(OtlpEndpointNormalizer.ENDPOINT, "http://otel-collector:4318");

        OtlpEndpointNormalizer.apply(environment);

        assertThat(environment.getProperty(OtlpEndpointNormalizer.ENDPOINT))
                .isEqualTo("http://otel-collector:4318/v1/traces");
        assertThat(environment.getProperty(OtlpEndpointNormalizer.EXPORT_ENABLED)).isNull();
    }

    @Test
    @DisplayName("A blank endpoint disables export instead of calling localhost")
    void blankEndpointDisablesExport() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(OtlpEndpointNormalizer.ENDPOINT, "  ");

        OtlpEndpointNormalizer.apply(environment);

        assertThat(environment.getProperty(OtlpEndpointNormalizer.EXPORT_ENABLED)).isEqualTo("false");
    }

    @Test
    @DisplayName("The prod profile samples 10 percent and does not export without a collector URL")
    void prodProfileDoesNotExportWithoutACollector() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        ConfigDataEnvironmentPostProcessor.applyTo(
                environment,
                new DefaultResourceLoader(),
                new DefaultBootstrapContext(),
                "prod");
        OtlpEndpointNormalizer.apply(environment);

        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("0.1");
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(environment.getProperty(OtlpEndpointNormalizer.EXPORT_ENABLED)).isEqualTo("false");
        assertThat(environment.getProperty(OtlpEndpointNormalizer.ENDPOINT)).isBlank();
    }

    @Test
    @DisplayName("The normalizer is registered for Spring Boot startup")
    void processorIsRegistered() throws IOException {
        try (var resource = getClass().getClassLoader().getResourceAsStream(
                "META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor")) {
            assertThat(resource).isNotNull();
            String listed = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(listed).contains(OtlpEndpointNormalizer.class.getName());
        }
    }
}
