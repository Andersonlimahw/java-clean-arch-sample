package com.muse.payments.shared.infrastructure.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalizes PaaS connection URLs ({@code DATABASE_URL}, {@code REDIS_URL},
 * {@code RABBITMQ_URL}, {@code CLOUDAMQP_URL}) into Spring Boot properties when
 * the explicit host/JDBC variables ({@code DB_URL}, {@code REDIS_HOST},
 * {@code RABBITMQ_HOST}) are not set.
 *
 * <p>Loaded via {@code META-INF/spring.factories} in Spring Boot 3.5.
 */
public final class ServiceUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "platformServiceUrls";

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        apply(environment);
    }

    static void apply(ConfigurableEnvironment env) {
        Map<String, Object> overrides = new LinkedHashMap<>();

        String databaseUrl = env.getProperty("DATABASE_URL");
        if (StringUtils.hasText(databaseUrl)
                && !StringUtils.hasText(env.getProperty("DB_URL"))
                && !hasExplicitOverride(env, "spring.datasource.url")) {
            applyDatabaseUrl(databaseUrl.trim(), env, overrides);
        }

        String redisUrl = env.getProperty("REDIS_URL");
        if (StringUtils.hasText(redisUrl)
                && !StringUtils.hasText(env.getProperty("REDIS_HOST"))
                && !hasExplicitOverride(env, "spring.data.redis.host")
                && !hasExplicitOverride(env, "spring.data.redis.url")) {
            String trimmed = redisUrl.trim();
            validateScheme(trimmed, "REDIS_URL", "redis", "rediss");
            overrides.put("spring.data.redis.url", trimmed);
        }

        String rabbitUrl = firstNonBlank(env.getProperty("RABBITMQ_URL"), env.getProperty("CLOUDAMQP_URL"));
        if (StringUtils.hasText(rabbitUrl)
                && !StringUtils.hasText(env.getProperty("RABBITMQ_HOST"))
                && !hasExplicitOverride(env, "spring.rabbitmq.host")
                && !hasExplicitOverride(env, "spring.rabbitmq.addresses")) {
            applyRabbitUrl(rabbitUrl.trim(), env, overrides);
        }

        if (!overrides.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, overrides));
        }
    }

    private static void applyDatabaseUrl(String rawUrl, ConfigurableEnvironment env, Map<String, Object> overrides) {
        if (rawUrl.startsWith("jdbc:postgresql://")) {
            overrides.put("spring.datasource.url", rawUrl);
            return;
        }
        URI uri = parseUri(rawUrl, "DATABASE_URL");
        String scheme = uri.getScheme();
        if (!"postgres".equalsIgnoreCase(scheme) && !"postgresql".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("Unsupported DATABASE_URL scheme: " + scheme);
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw new IllegalStateException("DATABASE_URL is missing host");
        }
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        jdbc.append(StringUtils.hasText(path) ? path : "/");
        if (StringUtils.hasText(uri.getRawQuery())) {
            jdbc.append('?').append(uri.getRawQuery());
        }
        overrides.put("spring.datasource.url", jdbc.toString());

        String userInfo = uri.getRawUserInfo();
        if (StringUtils.hasText(userInfo)) {
            int colon = userInfo.indexOf(':');
            String user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
            String pass = colon >= 0 ? userInfo.substring(colon + 1) : "";
            if (StringUtils.hasText(user) && !StringUtils.hasText(env.getProperty("DB_USER"))
                    && !hasExplicitOverride(env, "spring.datasource.username")) {
                overrides.put("spring.datasource.username", decode(user));
            }
            if (!StringUtils.hasText(env.getProperty("DB_PASSWORD"))
                    && !hasExplicitOverride(env, "spring.datasource.password")) {
                overrides.put("spring.datasource.password", decode(pass));
            }
        }
    }

    private static void applyRabbitUrl(String rawUrl, ConfigurableEnvironment env, Map<String, Object> overrides) {
        URI uri = parseUri(rawUrl, "RABBITMQ_URL/CLOUDAMQP_URL");
        String scheme = uri.getScheme();
        if (!"amqp".equalsIgnoreCase(scheme) && !"amqps".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("Unsupported RABBITMQ_URL/CLOUDAMQP_URL scheme: " + scheme);
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw new IllegalStateException("RABBITMQ_URL/CLOUDAMQP_URL is missing host");
        }
        boolean tls = "amqps".equalsIgnoreCase(scheme);
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 5671 : 5672);
        overrides.put("spring.rabbitmq.host", uri.getHost());
        overrides.put("spring.rabbitmq.port", String.valueOf(port));
        if (!StringUtils.hasText(env.getProperty("RABBITMQ_SSL_ENABLED"))
                && !hasExplicitOverride(env, "spring.rabbitmq.ssl.enabled")) {
            overrides.put("spring.rabbitmq.ssl.enabled", String.valueOf(tls));
        }

        String userInfo = uri.getRawUserInfo();
        if (StringUtils.hasText(userInfo)) {
            int colon = userInfo.indexOf(':');
            String user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
            String pass = colon >= 0 ? userInfo.substring(colon + 1) : "";
            if (StringUtils.hasText(user) && !StringUtils.hasText(env.getProperty("RABBITMQ_USER"))
                    && !hasExplicitOverride(env, "spring.rabbitmq.username")) {
                overrides.put("spring.rabbitmq.username", decode(user));
            }
            if (!StringUtils.hasText(env.getProperty("RABBITMQ_PASSWORD"))
                    && !hasExplicitOverride(env, "spring.rabbitmq.password")) {
                overrides.put("spring.rabbitmq.password", decode(pass));
            }
        }

        String rawPath = uri.getRawPath();
        if (StringUtils.hasText(rawPath) && !"/".equals(rawPath)
                && !StringUtils.hasText(env.getProperty("RABBITMQ_VHOST"))
                && !hasExplicitOverride(env, "spring.rabbitmq.virtual-host")) {
            overrides.put("spring.rabbitmq.virtual-host", decode(rawPath.substring(1)));
        }
    }

    private static URI parseUri(String rawUrl, String name) {
        try {
            return URI.create(rawUrl);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid " + name + " format", ex);
        }
    }

    private static void validateScheme(String rawUrl, String name, String... allowedSchemes) {
        URI uri = parseUri(rawUrl, name);
        String scheme = uri.getScheme();
        for (String allowed : allowedSchemes) {
            if (allowed.equalsIgnoreCase(scheme)) {
                return;
            }
        }
        throw new IllegalStateException("Unsupported " + name + " scheme: " + scheme);
    }

    private static boolean hasExplicitOverride(ConfigurableEnvironment env, String propertyName) {
        for (PropertySource<?> source : env.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                continue;
            }
            String sourceName = source.getName();
            if (sourceName.startsWith("Config resource ")) {
                continue;
            }
            if (source.containsProperty(propertyName)) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private static String decode(String encoded) {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
