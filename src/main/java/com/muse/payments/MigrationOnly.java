package com.muse.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Kubernetes migration Job mode. {@code APP_MIGRATION_ONLY=true} runs Flyway during
 * startup and then the process exits, so the Job can reach Complete. Web, Rabbit
 * listeners, and the outbox scheduler stay off so the Job is not a second API.
 */
public final class MigrationOnly {

    public static final String PROPERTY = "app.migration-only";
    static final String ENV = "APP_MIGRATION_ONLY";

    private MigrationOnly() {
    }

    public static boolean requested(String[] args) {
        return envRequests() || systemPropertyRequests() || argsRequest(args);
    }

    public static boolean enabled(Environment environment) {
        return Boolean.TRUE.equals(environment.getProperty(PROPERTY, Boolean.class, Boolean.FALSE));
    }

    public static void configure(SpringApplication application) {
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("migrationOnly", Map.of(
                        "spring.flyway.enabled", "true",
                        "spring.rabbitmq.listener.simple.auto-startup", "false",
                        "spring.rabbitmq.listener.direct.auto-startup", "false",
                        "app.outbox.scheduling.enabled", "false"
                ))));
    }

    static boolean envRequests() {
        return isTrue(System.getenv(ENV));
    }

    static boolean systemPropertyRequests() {
        return isTrue(System.getProperty(PROPERTY));
    }

    static boolean argsRequest(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if ("--app.migration-only=true".equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

    static boolean isTrue(String value) {
        return "true".equalsIgnoreCase(value);
    }
}
