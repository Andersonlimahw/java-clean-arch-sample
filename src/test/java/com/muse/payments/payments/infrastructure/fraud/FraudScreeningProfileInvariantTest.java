package com.muse.payments.payments.infrastructure.fraud;

import org.springframework.boot.convert.DurationStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FraudScreeningProfileInvariantTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"base", "local", "prod", "test"})
    @DisplayName("Each profile keeps the circuit window at least as large as the retry budget and a positive timeout")
    void profileInvariants(String profile) {
        Map<String, Object> root = merged(profile);
        Map<String, Object> instance = fraudInstance(root);
        int maxAttempts = number(retry(root).get("max-attempts"), 3);
        int minimumCalls = number(instance.get("minimum-number-of-calls"), 12);
        Duration timeout = DurationStyle.detectAndParse(timeoutText(root));
        Duration connect = DurationStyle.detectAndParse(String.valueOf(fraudApp(root).get("connect-timeout")));

        assertThat(minimumCalls).isGreaterThanOrEqualTo(maxAttempts);
        assertThat(timeout).isPositive();
        assertThat(connect).isPositive();
        assertThat(connect).isLessThanOrEqualTo(timeout);
        if ("prod".equals(profile)) {
            assertThat(read("application-prod.yml")).contains("base-url: ${FRAUD_SCREENING_BASE_URL}");
            assertThat(read("application-prod.yml")).doesNotContain("${FRAUD_SCREENING_BASE_URL:");
            assertThat(timeout).isLessThan(Duration.ofMillis(800));
        }
        if ("test".equals(profile)) {
            assertThat(retry(root).get("enable-exponential-backoff")).isEqualTo(false);
            assertThat(retry(root).get("enable-randomized-wait")).isEqualTo(false);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merged(String profile) {
        Map<String, Object> base = load("application.yml");
        if ("base".equals(profile)) {
            return base;
        }
        String file = "test".equals(profile) ? "application-test.yml" : "application-" + profile + ".yml";
        overlay(base, load(file));
        return base;
    }

    @SuppressWarnings("unchecked")
    private static void overlay(Map<String, Object> base, Map<String, Object> profile) {
        for (Map.Entry<String, Object> entry : profile.entrySet()) {
            Object existing = base.get(entry.getKey());
            if (existing instanceof Map<?, ?> && entry.getValue() instanceof Map<?, ?>) {
                overlay((Map<String, Object>) existing, (Map<String, Object>) entry.getValue());
            } else {
                base.put(entry.getKey(), entry.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fraudInstance(Map<String, Object> root) {
        Map<String, Object> resilience = (Map<String, Object>) root.get("resilience4j");
        Map<String, Object> circuit = (Map<String, Object>) resilience.get("circuitbreaker");
        Map<String, Object> instances = (Map<String, Object>) circuit.get("instances");
        return (Map<String, Object>) instances.get("fraudScreening");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> retry(Map<String, Object> root) {
        Map<String, Object> resilience = (Map<String, Object>) root.get("resilience4j");
        Map<String, Object> retry = (Map<String, Object>) resilience.get("retry");
        Map<String, Object> instances = (Map<String, Object>) retry.get("instances");
        return (Map<String, Object>) instances.get("fraudScreening");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fraudApp(Map<String, Object> root) {
        Map<String, Object> app = (Map<String, Object>) root.get("app");
        return (Map<String, Object>) app.get("fraud-screening");
    }

    @SuppressWarnings("unchecked")
    private static String timeoutText(Map<String, Object> root) {
        Map<String, Object> resilience = (Map<String, Object>) root.get("resilience4j");
        Map<String, Object> limiter = (Map<String, Object>) resilience.get("timelimiter");
        Map<String, Object> instances = (Map<String, Object>) limiter.get("instances");
        Map<String, Object> fraud = (Map<String, Object>) instances.get("fraudScreening");
        return String.valueOf(fraud.get("timeout-duration"));
    }

    private static int number(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static String read(String name) {
        try (InputStream in = FraudScreeningProfileInvariantTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + name);
            }
            return new String(in.readAllBytes());
        } catch (Exception ex) {
            throw new IllegalStateException("Could not read " + name, ex);
        }
    }

    private static Map<String, Object> load(String name) {
        InputStream main = FraudScreeningProfileInvariantTest.class.getClassLoader().getResourceAsStream(name);
        InputStream test = FraudScreeningProfileInvariantTest.class.getClassLoader().getResourceAsStream(name);
        InputStream source = main != null ? main : test;
        if (source == null && "application-test.yml".equals(name)) {
            source = Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
        }
        if (source == null) {
            throw new IllegalStateException("Missing " + name);
        }
        try (InputStream in = source) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new IllegalStateException(name + " is not a map");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not read " + name, ex);
        }
    }
}
