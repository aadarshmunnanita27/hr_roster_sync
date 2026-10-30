package com.moveinsync.roster.service;

import com.moveinsync.roster.config.MessagingConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {
    private static final Logger logger = LoggerFactory.getLogger(OutboxService.class);
    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final Duration publishTimeout;
    private final MeterRegistry meterRegistry;

    public OutboxService(
            JdbcTemplate jdbcTemplate,
            RabbitTemplate rabbitTemplate,
            MeterRegistry meterRegistry,
            @Value("${roster.timeouts.broker-publish:3s}") Duration publishTimeout) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.meterRegistry = meterRegistry;
        this.publishTimeout = publishTimeout;
    }

    @Scheduled(
            fixedDelayString = "${roster.outbox.poll-interval:1000}",
            initialDelayString = "${roster.outbox.initial-delay:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxMessage> messages = jdbcTemplate.query(
                """
                SELECT event_id, payload::text AS payload, attempts
                FROM outbox_events
                WHERE status = 'PENDING' AND next_attempt_at <= now()
                ORDER BY created_at
                LIMIT 50
                FOR UPDATE SKIP LOCKED
                """,
                (result, rowNumber) -> new OutboxMessage(
                        result.getObject("event_id", UUID.class),
                        result.getString("payload"),
                        result.getInt("attempts")));

        for (OutboxMessage message : messages) {
            try {
                CorrelationData confirmation = new CorrelationData(message.eventId().toString());
                rabbitTemplate.convertAndSend(
                        MessagingConfiguration.EXCHANGE,
                        "employee." + message.eventId(),
                        message.payload(),
                        confirmation);
                var result = confirmation.getFuture().get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (result == null || !result.isAck()) {
                    throw new IllegalStateException("Broker did not confirm the event");
                }
                jdbcTemplate.update(
                        """
                        UPDATE outbox_events
                        SET status = 'PUBLISHED', published_at = now(), attempts = attempts + 1
                        WHERE event_id = ?
                        """,
                        message.eventId());
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                int attempts = message.attempts() + 1;
                int delaySeconds = Math.min(300, 1 << Math.min(attempts, 8));
                jdbcTemplate.update(
                        """
                        UPDATE outbox_events
                        SET attempts = ?, next_attempt_at = now() + (? * interval '1 second'),
                            last_error_code = 'BROKER_PUBLISH_FAILED'
                        WHERE event_id = ?
                        """,
                        attempts, delaySeconds, message.eventId());
                meterRegistry.counter("outbox_publish_failures_total").increment();
                if (attempts > 1) {
                    meterRegistry.counter("outbox_publish_retries_total").increment();
                }
                logger.warn("outbox_publish_failed eventId={} errorCode=BROKER_PUBLISH_FAILED",
                        message.eventId());
            }
        }
    }

    private record OutboxMessage(UUID eventId, String payload, int attempts) {
    }
}
