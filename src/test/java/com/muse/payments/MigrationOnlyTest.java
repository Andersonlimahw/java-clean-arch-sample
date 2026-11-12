package com.muse.payments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationOnlyTest {

    @Test
    @DisplayName("only the literal true enables migration-only mode")
    void isTrueAcceptsTrueIgnoreCase() {
        assertThat(MigrationOnly.isTrue(null)).isFalse();
        assertThat(MigrationOnly.isTrue("")).isFalse();
        assertThat(MigrationOnly.isTrue("false")).isFalse();
        assertThat(MigrationOnly.isTrue("1")).isFalse();
        assertThat(MigrationOnly.isTrue("true")).isTrue();
        assertThat(MigrationOnly.isTrue("TRUE")).isTrue();
    }

    @Test
    @DisplayName("command line and system property request a migration Job")
    void argsAndSystemPropertyRequestMigrationOnly() {
        assertThat(MigrationOnly.argsRequest(new String[] {})).isFalse();
        assertThat(MigrationOnly.argsRequest(new String[] {"--app.migration-only=false"})).isFalse();
        assertThat(MigrationOnly.argsRequest(new String[] {"--app.migration-only=true"})).isTrue();

        String key = MigrationOnly.PROPERTY;
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "true");
            assertThat(MigrationOnly.systemPropertyRequests()).isTrue();
            System.setProperty(key, "false");
            assertThat(MigrationOnly.systemPropertyRequests()).isFalse();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    @DisplayName("bound app.migration-only property is what main checks after startup")
    void enabledReadsBoundProperty() {
        MockEnvironment off = new MockEnvironment();
        assertThat(MigrationOnly.enabled(off)).isFalse();

        MockEnvironment on = new MockEnvironment().withProperty(MigrationOnly.PROPERTY, "true");
        assertThat(MigrationOnly.enabled(on)).isTrue();
    }

    @Test
    @DisplayName("migration-only disables the web server, listeners, and outbox scheduler, and forces Flyway")
    void configureForcesJobBehavior() {
        SpringApplication application = new SpringApplication(PaymentsApplication.class);
        MigrationOnly.configure(application);

        assertThat(application.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);

        ConfigurableApplicationContext context = new AnnotationConfigApplicationContext();
        for (ApplicationContextInitializer<?> initializer : application.getInitializers()) {
            @SuppressWarnings("unchecked")
            ApplicationContextInitializer<ConfigurableApplicationContext> typed =
                    (ApplicationContextInitializer<ConfigurableApplicationContext>) initializer;
            typed.initialize(context);
        }

        assertThat(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(context.getEnvironment().getProperty("spring.rabbitmq.listener.simple.auto-startup", Boolean.class)).isFalse();
        assertThat(context.getEnvironment().getProperty("spring.rabbitmq.listener.direct.auto-startup", Boolean.class)).isFalse();
        assertThat(context.getEnvironment().getProperty("app.outbox.scheduling.enabled", Boolean.class)).isFalse();
        context.close();
    }
}
