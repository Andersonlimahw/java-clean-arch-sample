package com.muse.payments;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.security.SecureRandom;
import java.util.Base64;

abstract class SharedContainers {

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

    /**
     * One random KEK per JVM, generated at runtime and never written anywhere. Every context shares
     * the database, so every context must unwrap the same data keys, including the prod-profile one.
     */
    private static final String TEST_KEK_BASE64;

    static {
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        TEST_KEK_BASE64 = Base64.getEncoder().encodeToString(kek);
        postgres.start();
        rabbit.start();
        redis.start();
    }

    @DynamicPropertySource
    static void lgpdKeyEncryptionKey(DynamicPropertyRegistry registry) {
        registry.add("app.lgpd.kek-base64", () -> TEST_KEK_BASE64);
        registry.add("app.lgpd.kek-id", () -> "lgpd-kek-test");
    }
}
