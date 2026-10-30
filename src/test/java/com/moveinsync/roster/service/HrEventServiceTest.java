package com.moveinsync.roster.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import com.moveinsync.roster.core.EventType;
import com.moveinsync.roster.core.FieldOwnershipPolicy;
import com.moveinsync.roster.core.HrFingerprintService;
import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.dto.EventProcessingResult;
import com.moveinsync.roster.dto.HrEventRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class HrEventServiceTest {
    @Test
    void handlesEachRecordIndependentlyAndDoesNotEchoInvalidPersonalData() {
        Cache<String, Boolean> officeCache = Caffeine.newBuilder().build();
        officeCache.put("tenant-a:BLR01", true);
        HrEventHandler appliedHandler = new HrEventHandler() {
            @Override
            public EventType eventType() {
                return EventType.CREATE;
            }

            @Override
            public EventProcessingResult handle(TenantId tenantId, ValidatedHrEvent event) {
                return EventProcessingResult.applied(event.employeeId().value(), "APPLIED");
            }
        };
        HrEventService service = new HrEventService(
                List.of(appliedHandler),
                new FieldOwnershipPolicy(),
                new HrFingerprintService(),
                new JdbcTemplate(),
                officeCache,
                new SimpleMeterRegistry());

        HrEventRequest valid = new HrEventRequest(null, "EMP-1", "hr", 1L, "CREATE",
                Map.of("name", "Example Worker", "email", "worker@example.invalid",
                        "phone", "+10000000000", "officeCode", "BLR01", "shift", "DAY"));
        String invalidPhone = "phone-must-not-be-echoed";
        HrEventRequest invalid = new HrEventRequest(null, "EMP-2", "hr", 1L, "CREATE",
                Map.of("name", "Example Worker", "email", "worker@example.invalid",
                        "phone", invalidPhone, "officeCode", "BLR01", "shift", "DAY"));

        List<EventProcessingResult> results = service.process(new TenantId("tenant-a"), List.of(valid, invalid));

        assertEquals("APPLIED", results.get(0).status());
        assertEquals("REJECTED", results.get(1).status());
        assertEquals("INVALID_PHONE", results.get(1).errorCode());
        assertEquals("phone", results.get(1).field());
        assertEquals(false, results.toString().contains(invalidPhone));
    }
}
