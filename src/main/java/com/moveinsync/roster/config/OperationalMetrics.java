package com.moveinsync.roster.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OperationalMetrics {
    public OperationalMetrics(
            MeterRegistry meterRegistry, JdbcTemplate jdbcTemplate, RabbitAdmin rabbitAdmin) {
        Gauge.builder("outbox_pending_events", jdbcTemplate, database -> value(database,
                        "SELECT count(*) FROM outbox_events WHERE status = 'PENDING'"))
                .register(meterRegistry);
        Gauge.builder("outbox_oldest_pending_age_seconds", jdbcTemplate, database -> value(database,
                        """
                        SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)
                        FROM outbox_events WHERE status = 'PENDING'
                        """))
                .register(meterRegistry);
        Gauge.builder("rabbitmq_queue_depth", rabbitAdmin, admin -> {
            var queue = admin.getQueueInfo(MessagingConfiguration.QUEUE);
            return queue == null ? 0 : queue.getMessageCount();
        }).register(meterRegistry);
    }

    private double value(JdbcTemplate jdbcTemplate, String sql) {
        Number value = jdbcTemplate.queryForObject(sql, Number.class);
        return value == null ? 0 : value.doubleValue();
    }
}
