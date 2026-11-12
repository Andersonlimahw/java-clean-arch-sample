package com.muse.payments;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.Lifecycle;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.util.Map;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentsApplication {

    private static final Logger log = LoggerFactory.getLogger(PaymentsApplication.class);

    /**
     * Inserted ahead of every other property source. The Kubernetes ConfigMap sets
     * {@code SPRING_FLYWAY_ENABLED=false} for the Deployment, and that variable would
     * otherwise outrank {@code application-migration.yml}.
     */
    private static final Map<String, Object> MIGRATION_OVERRIDES = Map.of(
            "spring.flyway.enabled", "true",
            "spring.main.web-application-type", "none",
            "spring.rabbitmq.listener.simple.auto-startup", "false",
            "app.outbox.scheduling.enabled", "false",
            "spring.modulith.moments.enabled", "false"
    );

    public static void main(String[] args) {
        ApplicationLaunch launch = launch(args);
        if (launch.migrationOnly()) {
            System.exit(launch.exitCode());
        }
    }

    /**
     * Starts the application. In migration mode the context is closed and {@code exitCode}
     * is the status {@link #main} passes to {@code System.exit}: {@code 0} only when Flyway
     * has applied the schema and neither HTTP nor consumers are running; non-zero when
     * startup or that check fails. Callers other than {@link #main} must not expect the JVM
     * to halt — the exit code is returned so tests can observe it.
     */
    static ApplicationLaunch launch(String[] args) {
        SpringApplication application = new SpringApplication(PaymentsApplication.class);
        boolean migrationOnly = MigrationOnlyMode.enabled(args);
        if (migrationOnly) {
            application.setAdditionalProfiles("migration");
            application.setWebApplicationType(WebApplicationType.NONE);
            application.addListeners(new MigrationPropertyOverrides());
            log.info("Migration-only mode: Flyway will run and the process will exit");
        }
        ConfigurableApplicationContext context = null;
        try {
            context = application.run(args);
            WebApplicationType webType = application.getWebApplicationType();
            if (!migrationOnly) {
                ConfigurableApplicationContext running = context;
                context = null;
                return new ApplicationLaunch(false, 0, webType, running);
            }
            int code = migrationExitCode(context);
            context = null;
            return new ApplicationLaunch(true, code, webType, null);
        } catch (RuntimeException ex) {
            if (!migrationOnly) {
                throw ex;
            }
            log.error("Migration failed", ex);
            closeQuietly(context);
            return new ApplicationLaunch(true, 1, application.getWebApplicationType(), null);
        }
    }

    private static int migrationExitCode(ConfigurableApplicationContext context) {
        String problem = migrationProblem(context);
        final int code = problem == null ? 0 : 1;
        if (problem != null) {
            log.error(problem);
        } else {
            log.info("Flyway migrations applied; shutting down migration process");
        }
        return SpringApplication.exit(context, () -> code);
    }

    private static String migrationProblem(ConfigurableApplicationContext context) {
        if (context instanceof WebServerApplicationContext web && web.getWebServer() != null) {
            return "Migration mode started an HTTP server";
        }
        if (rabbitListenersRunning(context)) {
            return "Migration mode started Rabbit listeners";
        }
        if (context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class).length > 0) {
            return "Migration mode started the outbox scheduler";
        }
        Flyway flyway;
        try {
            flyway = context.getBean(Flyway.class);
        } catch (RuntimeException ex) {
            log.error("Flyway bean is missing", ex);
            return "Flyway is not active in migration mode";
        }
        if (flyway.info().pending().length > 0) {
            return "Flyway left pending migrations";
        }
        if (flyway.info().applied().length == 0) {
            return "Flyway applied no migrations";
        }
        return null;
    }

    private static boolean rabbitListenersRunning(ConfigurableApplicationContext context) {
        return context.getBeansOfType(RabbitListenerEndpointRegistry.class).values().stream()
                .flatMap(registry -> registry.getListenerContainers().stream())
                .anyMatch(Lifecycle::isRunning);
    }

    private static void closeQuietly(ConfigurableApplicationContext context) {
        if (context == null || !context.isActive()) {
            return;
        }
        try {
            SpringApplication.exit(context, () -> 1);
        } catch (RuntimeException ex) {
            log.warn("Failed to close the migration context cleanly", ex);
        }
    }

    /**
     * Runs before {@code spring.main.*} is bound, so the web stack stays off and Flyway
     * stays on even when the environment variable or the command line says otherwise.
     */
    private static final class MigrationPropertyOverrides
            implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            event.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("migrationOnly", MIGRATION_OVERRIDES));
        }
    }
}
