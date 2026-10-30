package com.moveinsync.roster.service;

import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    public BookingService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void cancelFutureConfirmed(String tenantId, String employeeId, UUID eventId) {
        int firstDelivery = jdbcTemplate.update(
                "INSERT INTO processed_messages (message_id) VALUES (?) ON CONFLICT DO NOTHING",
                eventId);
        if (firstDelivery == 0) {
            return;
        }

        Integer cancelled = jdbcTemplate.queryForObject(
                """
                WITH cancelled AS (
                    UPDATE bookings
                    SET status = 'CANCELLED'
                    WHERE tenant_id = ? AND employee_id = ? AND status = 'CONFIRMED'
                      AND scheduled_at > now()
                    RETURNING booking_id
                ), released AS (
                    UPDATE cab_seats
                    SET occupied = false, booking_id = NULL
                    WHERE booking_id IN (SELECT booking_id FROM cancelled)
                    RETURNING seat_id
                )
                SELECT count(*) FROM cancelled
                """,
                Integer.class, tenantId, employeeId);
        if (cancelled != null && cancelled > 0) {
            meterRegistry.counter("booking_cancellations_total").increment(cancelled);
        }
    }
}
