package com.muse.payments.shared.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceUrlEnvironmentPostProcessorTest {

    @Test
    void translatesPaaSUrlsEvenWhenSpringBootConfigurationPropertySourceIsAttached() {
        StandardEnvironment env = isolatedEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("paasEnv", java.util.Map.of(
                "DATABASE_URL", "postgres://app_user:s3cr3t%21%40@db.railway.internal:5432/payments?sslmode=require",
                "REDIS_URL", "rediss://default:secret@redis.upstash.io:6379",
                "CLOUDAMQP_URL", "amqps://mq_user:mq_pass%23@puffin.rmq2.cloudamqp.com/prod-vhost"
        )));
        ConfigurationPropertySources.attach(env);
        ConfigDataEnvironmentPostProcessor.applyTo(env, new DefaultResourceLoader(), new DefaultBootstrapContext(), "local");

        ServiceUrlEnvironmentPostProcessor.apply(env);

        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://db.railway.internal:5432/payments?sslmode=require");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("app_user");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("s3cr3t!@");

        assertThat(env.getProperty("spring.data.redis.url")).isEqualTo("rediss://default:secret@redis.upstash.io:6379");

        assertThat(env.getProperty("spring.rabbitmq.host")).isEqualTo("puffin.rmq2.cloudamqp.com");
        assertThat(env.getProperty("spring.rabbitmq.port")).isEqualTo("5671");
        assertThat(env.getProperty("spring.rabbitmq.ssl.enabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.rabbitmq.username")).isEqualTo("mq_user");
        assertThat(env.getProperty("spring.rabbitmq.password")).isEqualTo("mq_pass#");
        assertThat(env.getProperty("spring.rabbitmq.virtual-host")).isEqualTo("prod-vhost");
    }

    @Test
    void decodesEncodedRootVirtualHostInAmqpUrl() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("RABBITMQ_URL", "amqp://paas_mq:paas_mq_pass@mq:5672/%2F");

        ServiceUrlEnvironmentPostProcessor.apply(env);

        assertThat(env.getProperty("spring.rabbitmq.host")).isEqualTo("mq");
        assertThat(env.getProperty("spring.rabbitmq.port")).isEqualTo("5672");
        assertThat(env.getProperty("spring.rabbitmq.ssl.enabled")).isEqualTo("false");
        assertThat(env.getProperty("spring.rabbitmq.virtual-host")).isEqualTo("/");
    }

    @Test
    void explicitDbUrlOutranksDatabaseUrl() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DB_URL", "jdbc:postgresql://explicit-db:5432/payments");
        env.setProperty("DATABASE_URL", "postgres://ignored:ignored@paas-db:5432/other");

        ServiceUrlEnvironmentPostProcessor.apply(env);

        assertThat(env.getPropertySources().contains(ServiceUrlEnvironmentPostProcessor.SOURCE_NAME)).isFalse();
    }

    @Test
    void rejectsUnsupportedServiceSchemes() {
        MockEnvironment badDb = new MockEnvironment();
        badDb.setProperty("DATABASE_URL", "mysql://root:pass@localhost:3306/payments");
        assertThatThrownBy(() -> ServiceUrlEnvironmentPostProcessor.apply(badDb))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_URL");

        MockEnvironment badRedis = new MockEnvironment();
        badRedis.setProperty("REDIS_URL", "http://localhost:6379");
        assertThatThrownBy(() -> ServiceUrlEnvironmentPostProcessor.apply(badRedis))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS_URL");
    }

    private static StandardEnvironment isolatedEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return environment;
    }
}
