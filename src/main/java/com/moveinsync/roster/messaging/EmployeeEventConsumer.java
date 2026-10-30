package com.moveinsync.roster.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.config.MessagingConfiguration;
import com.moveinsync.roster.service.BookingService;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class EmployeeEventConsumer {
    private final ObjectMapper objectMapper;
    private final BookingService bookingService;

    public EmployeeEventConsumer(ObjectMapper objectMapper, BookingService bookingService) {
        this.objectMapper = objectMapper;
        this.bookingService = bookingService;
    }

    @RabbitListener(queues = MessagingConfiguration.QUEUE)
    public void consume(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        if (!"EMPLOYEE_DEACTIVATED".equals(event.path("eventType").asText())) {
            return;
        }
        bookingService.cancelFutureConfirmed(
                event.path("tenantId").asText(),
                event.path("employeeId").asText(),
                UUID.fromString(event.path("eventId").asText()));
    }
}
