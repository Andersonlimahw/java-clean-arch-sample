package com.muse.payments;

import com.muse.payments.support.ControllableFraudScreening;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * The broker is static and shared by every Spring test in the JVM. A context left open
 * keeps its notification listener, which would ack messages a later test expects to retry
 * or dead-letter. Close each context when its class finishes.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class TestcontainersBase {

    @Autowired
    private ObjectProvider<ControllableFraudScreening> fraudScreening;

    @BeforeEach
    void resetFraudScreeningBefore() {
        if (fraudScreening != null) {
            fraudScreening.ifAvailable(ControllableFraudScreening::reset);
        }
    }

    @AfterEach
    void resetFraudScreeningAfter() {
        if (fraudScreening != null) {
            fraudScreening.ifAvailable(ControllableFraudScreening::reset);
        }
    }


    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payments_test")
            .withUsername("test")
            .withPassword("test");

    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-alpine");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        postgres.start();
        rabbit.start();
        redis.start();
    }
}
