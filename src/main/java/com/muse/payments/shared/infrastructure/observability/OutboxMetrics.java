package com.muse.payments.shared.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Outbox health derived from PostgreSQL, which stays the source of truth between commit and publish.
 * Gauges are scraped by Prometheus; a query failure reports NaN instead of failing the request path.
 */
@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    public OutboxMetrics(MeterRegistry meterRegistry, JdbcTemplate jdbcTemplate) {
        Gauge.builder("payments.outbox.pending", jdbcTemplate,
                        jdbc -> query(jdbc, "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL"))
                .description("Unpublished outbox rows")
                .register(meterRegistry);
        Gauge.builder("payments.outbox.lag.seconds", jdbcTemplate, OutboxMetrics::lagSeconds)
                .description("Age in seconds of the oldest unpublished outbox row")
                .register(meterRegistry);
        Gauge.builder("payments.outbox.attempts", jdbcTemplate,
                        jdbc -> query(jdbc, "SELECT COALESCE(SUM(attempts), 0) FROM outbox_events WHERE published_at IS NULL"))
                .description("Accumulated publish attempts on unpublished outbox rows")
                .register(meterRegistry);
    }

    private static double lagSeconds(JdbcTemplate jdbc) {
        return query(jdbc, """
                SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(occurred_at))), 0)
                FROM outbox_events
                WHERE published_at IS NULL
                """);
    }

    private static double query(JdbcTemplate jdbc, String sql) {
        try {
            Double value = jdbc.queryForObject(sql, Double.class);
            return value == null ? 0d : value;
        } catch (RuntimeException ex) {
            log.warn("Outbox metric query failed: {}", ex.getMessage());
            return Double.NaN;
        }
    }
}
