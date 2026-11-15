package com.muse.payments;

import com.muse.payments.shared.infrastructure.SchedulingConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("migration")
class MigrationProfileIT extends TestcontainersBase {

    @Autowired
    private Flyway flyway;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Migration profile applies Flyway and does not start consumers or the outbox scheduler")
    void flywayRunsWithoutConsumers() {
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(listenerRegistry.getListenerContainers()).isNotEmpty();
        assertThat(listenerRegistry.getListenerContainers()).allSatisfy(container -> {
            assertThat(container.isAutoStartup()).isFalse();
            assertThat(container.isRunning()).isFalse();
        });
        assertThat(applicationContext.getBeansOfType(SchedulingConfig.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }
}
