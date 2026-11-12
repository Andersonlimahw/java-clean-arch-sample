package com.muse.payments.shared.infrastructure.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

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
    @DisplayName("Spring Boot 3.5 loads the normalizer from META-INF/spring.factories")
    void processorIsRegisteredInSpringFactories() throws IOException {
        Enumeration<URL> resources = getClass().getClassLoader().getResources("META-INF/spring.factories");
        StringBuilder listed = new StringBuilder();
        while (resources.hasMoreElements()) {
            try (var resource = resources.nextElement().openStream()) {
                listed.append(new String(resource.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
        }
        assertThat(listed.toString()).contains(
                "org.springframework.boot.env.EnvironmentPostProcessor="
                        + OtlpEndpointNormalizer.class.getName());
    }

    @Test
    @DisplayName("Spring Boot startup rewrites a collector base URL to the traces path")
    void springBootStartupRewritesBaseUrl() {
        SpringApplication application = probe();
        try (ConfigurableApplicationContext context = application.run(
                "--OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318")) {
            ConfigurableEnvironment environment = context.getEnvironment();
            assertThat(environment.getProperty(OtlpEndpointNormalizer.ENDPOINT))
                    .isEqualTo("http://127.0.0.1:4318/v1/traces");
            assertThat(environment.getProperty(OtlpEndpointNormalizer.EXPORT_ENABLED)).isNull();
        }
    }

    @Test
    @DisplayName("Spring Boot startup on prod disables export when no collector URL is set")
    void springBootStartupProdDisablesExportWithoutUrl() {
        SpringApplication application = probe();
        application.setAdditionalProfiles("prod");
        try (ConfigurableApplicationContext context = application.run()) {
            ConfigurableEnvironment environment = context.getEnvironment();
            assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("0.1");
            assertThat(environment.getProperty(OtlpEndpointNormalizer.EXPORT_ENABLED)).isEqualTo("false");
            assertThat(environment.getProperty(OtlpEndpointNormalizer.ENDPOINT)).isBlank();
        }
    }

    private static SpringApplication probe() {
        SpringApplication application = new SpringApplication(NormalizerProbe.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        return application;
    }

    /**
     * Not a {@code @SpringBootConfiguration}. Spring's test finder would otherwise
     * boot this class instead of {@code PaymentsApplication} for tests in this package.
     */
    static class NormalizerProbe {
    }
}
