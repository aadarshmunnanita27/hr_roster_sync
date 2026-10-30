package com.moveinsync.roster.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.dto.HrEventRequest;
import com.moveinsync.roster.dto.SnapshotRunResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "roster.outbox.initial-delay=3600000",
                "roster.outbox.poll-interval=3600000"
        })
class RosterIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4");

    @Container
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:3.13-management")
                    .withUser("roster-test", "roster-test-password")
                    .withPermission("/", "roster-test", ".*", ".*", ".*");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HrEventService hrEventService;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private TenantSettingsService tenantSettingsService;

    @Autowired
    private SnapshotService snapshotService;

    @DynamicPropertySource
    static void serviceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "roster-test");
        registry.add("spring.rabbitmq.password", () -> "roster-test-password");
        registry.add("roster.jwt.secret", () -> "integration-test-secret-with-at-least-32-bytes");
    }

    @BeforeEach
    void addOffice() {
        jdbcTemplate.update(
                """
                INSERT INTO offices (tenant_id, office_code, office_name) VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, office_code) DO NOTHING
                """,
                "integration-tenant", "BLR01", "Test office");
        jdbcTemplate.update(
                """
                INSERT INTO offices (tenant_id, office_code, office_name) VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, office_code) DO NOTHING
                """,
                "integration-tenant", "BLR02", "Second test office");
        tenantSettingsService.updateSafetyThreshold(
                "integration-tenant", java.math.BigDecimal.valueOf(30));
    }

    @Test
    void replayingAnEventDoesNotWriteAnotherOutboxRow() {
        String employeeId = "EMP-" + UUID.randomUUID();
        HrEventRequest event = event(employeeId, 7, "BLR01", "NIGHT", "CREATE");

        assertEquals("APPLIED", result(event).get(0).status());
        assertEquals("DUPLICATE", result(event).get(0).status());
        assertEquals(1, countOutbox(employeeId));
    }

    @Test
    void higherVersionWinsInEitherArrivalOrderAndEqualVersionChangesConflict() {
        String outOfOrderId = "EMP-" + UUID.randomUUID();
        assertEquals("APPLIED", result(event(outOfOrderId, 7, "BLR02", "NIGHT", "CREATE")).get(0).status());
        assertEquals("STALE_IGNORED", result(event(outOfOrderId, 6, "BLR01", "DAY", "UPDATE")).get(0).status());

        String inOrderId = "EMP-" + UUID.randomUUID();
        result(event(inOrderId, 6, "BLR01", "DAY", "CREATE"));
        assertEquals("APPLIED", result(event(inOrderId, 7, "BLR02", "NIGHT", "UPDATE")).get(0).status());
        assertEquals("VERSION_CONFLICT",
                result(event(inOrderId, 7, "BLR01", "DAY", "UPDATE")).get(0).status());

        Map<String, Object> employee = jdbcTemplate.queryForMap(
                "SELECT hr_version, office_code, shift FROM employees WHERE tenant_id = ? AND employee_id = ?",
                "integration-tenant", inOrderId);
        assertEquals(7L, ((Number) employee.get("hr_version")).longValue());
        assertEquals("BLR02", employee.get("office_code"));
        assertEquals("NIGHT", employee.get("shift"));
    }

    @Test
    void lowerVersionsAreIgnoredAndAChangeWithTheSameVersionIsRejected() {
        String employeeId = "EMP-" + UUID.randomUUID();
        result(event(employeeId, 6, "BLR01", "DAY", "CREATE"));
        assertEquals("APPLIED", result(event(employeeId, 7, "BLR02", "NIGHT", "UPDATE")).get(0).status());
        assertEquals("STALE_IGNORED", result(event(employeeId, 5, "BLR01", "DAY", "UPDATE")).get(0).status());
        assertEquals("VERSION_CONFLICT",
                result(event(employeeId, 7, "BLR01", "DAY", "UPDATE")).get(0).status());

        assertEquals("BLR02", jdbcTemplate.queryForObject(
                "SELECT office_code FROM employees WHERE tenant_id = ? AND employee_id = ?",
                String.class, "integration-tenant", employeeId));
        assertEquals("NIGHT", jdbcTemplate.queryForObject(
                "SELECT shift FROM employees WHERE tenant_id = ? AND employee_id = ?",
                String.class, "integration-tenant", employeeId));
    }

    @Test
    void hrPayloadCannotOverwriteTransportPreferences() {
        String employeeId = "EMP-" + UUID.randomUUID();
        result(event(employeeId, 1, "BLR01", "DAY", "CREATE"));
        jdbcTemplate.update(
                "UPDATE employees SET pickup_point = ?, special_assistance = true "
                        + "WHERE tenant_id = ? AND employee_id = ?",
                "Central Station", "integration-tenant", employeeId);
        Map<String, Object> data = new HashMap<>(event(employeeId, 2, "BLR02", "NIGHT", "UPDATE").data());
        data.put("pickupPoint", "Unapproved change");

        assertEquals("REJECTED",
                result(new HrEventRequest(null, employeeId, "test-hr", 2L, "UPDATE", data)).get(0).status());
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                """
                SELECT office_code, pickup_point, special_assistance, hr_version
                FROM employees WHERE tenant_id = ? AND employee_id = ?
                """,
                "integration-tenant", employeeId);
        assertEquals("BLR01", stored.get("office_code"));
        assertEquals("Central Station", stored.get("pickup_point"));
        assertEquals(true, stored.get("special_assistance"));
        assertEquals(1L, ((Number) stored.get("hr_version")).longValue());
    }

    @Test
    void employeeIdsAreScopedByTenantAndSource() {
        String employeeId = "EMP-" + UUID.randomUUID();
        result(event(employeeId, 1, "BLR01", "DAY", "CREATE"));
        jdbcTemplate.update(
                """
                INSERT INTO offices (tenant_id, office_code, office_name) VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, office_code) DO NOTHING
                """,
                "another-tenant", "BLR01", "Other tenant office");
        hrEventService.process(new TenantId("another-tenant"),
                List.of(new HrEventRequest(null, employeeId, "another-hr", 1L, "CREATE",
                        event(employeeId, 1, "BLR01", "NIGHT", "CREATE").data())));

        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM employees WHERE employee_id = ?", Integer.class, employeeId));
        assertEquals("SOURCE_SYSTEM_CONFLICT", result(
                new HrEventRequest(null, employeeId, "other-hr", 2L, "UPDATE",
                        event(employeeId, 2, "BLR02", "DAY", "UPDATE").data())).get(0).errorCode());
    }

    @Test
    void higherVersionWinsWhenRequestsArriveConcurrently() throws Exception {
        String employeeId = "EMP-" + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var older = executor.submit(() -> processAfterStart(
                    event(employeeId, 6, "BLR01", "DAY", "CREATE"), ready, start));
            var newer = executor.submit(() -> processAfterStart(
                    event(employeeId, 7, "BLR01", "NIGHT", "CREATE"), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            older.get(10, TimeUnit.SECONDS);
            newer.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Map<String, Object> employee = jdbcTemplate.queryForMap(
                "SELECT hr_version, shift FROM employees WHERE tenant_id = ? AND employee_id = ?",
                "integration-tenant", employeeId);
        assertEquals(7L, ((Number) employee.get("hr_version")).longValue());
        assertEquals("NIGHT", employee.get("shift"));
    }

    @Test
    void committedOutboxEventCanBePublishedAfterTheOriginalRequestReturns() {
        String employeeId = "EMP-" + UUID.randomUUID();
        result(event(employeeId, 1, "BLR01", "DAY", "CREATE"));
        assertEquals(1, countOutbox(employeeId));

        outboxService.publishPending();

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM outbox_events WHERE employee_id = ? AND status = 'PUBLISHED'",
                        Integer.class, employeeId)));
    }

    @Test
    void duplicateDeactivationDeliveryReleasesASeatOnlyOnce() {
        String employeeId = "EMP-" + UUID.randomUUID();
        result(event(employeeId, 1, "BLR01", "DAY", "CREATE"));
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO bookings (booking_id, tenant_id, employee_id, seat_id, status, scheduled_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', now() + interval '1 day')
                """,
                bookingId, "integration-tenant", employeeId, seatId);
        jdbcTemplate.update(
                "INSERT INTO cab_seats (seat_id, tenant_id, booking_id, occupied) VALUES (?, ?, ?, true)",
                seatId, "integration-tenant", bookingId);

        result(new HrEventRequest(null, employeeId, "test-hr", 2L, "DEACTIVATE", Map.of()));
        outboxService.publishPending();

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                        "SELECT status FROM bookings WHERE booking_id = ?", String.class, bookingId)));
        UUID eventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM outbox_events WHERE employee_id = ? AND event_type = 'EMPLOYEE_DEACTIVATED'",
                UUID.class, employeeId);
        bookingService.cancelFutureConfirmed("integration-tenant", employeeId, eventId);
        assertEquals(false, jdbcTemplate.queryForObject(
                "SELECT occupied FROM cab_seats WHERE seat_id = ?", Boolean.class, seatId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE message_id = ?", Integer.class, eventId));
    }

    @Test
    void truncatedSnapshotWaitsForAnAuditedOperatorDecision() {
        String employeeId = "EMP-" + UUID.randomUUID();
        result(event(employeeId, 1, "BLR01", "DAY", "CREATE"));
        String csv = "employeeId,sourceVersion,name,email,phone,officeCode,shift\n";
        SnapshotRunResponse run = snapshotService.upload(
                new TenantId("integration-tenant"),
                "test-hr",
                OffsetDateTime.now().plusMinutes(1),
                null,
                null,
                new MockMultipartFile("file", "truncated.csv", "text/csv",
                        csv.getBytes(StandardCharsets.UTF_8)));

        assertEquals("APPROVAL_REQUIRED", run.state());
        assertEquals(1L, run.missingEmployeeCount());
        assertEquals(true, jdbcTemplate.queryForObject(
                "SELECT active FROM employees WHERE tenant_id = ? AND employee_id = ?",
                Boolean.class, "integration-tenant", employeeId));
        SnapshotRunResponse rejected = snapshotService.reject(
                new TenantId("integration-tenant"), run.runId(), "operator-1", "Unexpectedly short roster");
        assertEquals("REJECTED", rejected.state());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE run_id = ?", Integer.class, run.runId()));
    }

    @Test
    void invalidSnapshotRowProtectsTheMatchingEmployeeFromDeactivation() {
        String employeeId = "EMP-" + UUID.randomUUID();
        result(event(employeeId, 1, "BLR01", "DAY", "CREATE"));
        String csv = """
                employeeId,sourceVersion,name,email,phone,officeCode,shift
                %s,2,Example Worker,worker@example.invalid,invalid-phone,BLR01,NIGHT
                """.formatted(employeeId);
        SnapshotRunResponse run = snapshotService.upload(
                new TenantId("integration-tenant"),
                "test-hr",
                OffsetDateTime.now().plusMinutes(1),
                null,
                null,
                new MockMultipartFile("file", "invalid.csv", "text/csv",
                        csv.getBytes(StandardCharsets.UTF_8)));

        assertEquals("COMPLETED", run.state());
        assertEquals(true, jdbcTemplate.queryForObject(
                "SELECT active FROM employees WHERE tenant_id = ? AND employee_id = ?",
                Boolean.class, "integration-tenant", employeeId));
        assertEquals("INVALID_PHONE", jdbcTemplate.queryForObject(
                "SELECT error_code FROM snapshot_errors WHERE run_id = ?",
                String.class, run.runId()));
    }

    @Test
    void duplicateSnapshotIdsAreReportedAndExcludedFromApply() {
        String employeeId = "EMP-" + UUID.randomUUID();
        String csv = """
                employeeId,sourceVersion,name,email,phone,officeCode,shift
                %1$s,1,Example Worker,worker@example.invalid,+10000000000,BLR01,DAY
                %1$s,1,Example Worker,worker@example.invalid,+10000000000,BLR01,DAY
                """.formatted(employeeId);
        SnapshotRunResponse run = snapshotService.upload(
                new TenantId("integration-tenant"), "test-hr", OffsetDateTime.now().plusMinutes(1),
                null, null, multipart(csv));

        assertEquals("COMPLETED", run.state());
        assertEquals("DUPLICATE_EMPLOYEE_ID", jdbcTemplate.queryForObject(
                "SELECT error_code FROM snapshot_errors WHERE run_id = ?", String.class, run.runId()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM employees WHERE tenant_id = ? AND employee_id = ?",
                Integer.class, "integration-tenant", employeeId));
    }

    @Test
    void manifestMismatchLeavesTheRunFailedAndAppliesNothing() {
        String employeeId = "EMP-" + UUID.randomUUID();
        String csv = """
                employeeId,sourceVersion,name,email,phone,officeCode,shift
                %s,1,Example Worker,worker@example.invalid,+10000000000,BLR01,DAY
                """.formatted(employeeId);
        SnapshotRunResponse run = snapshotService.upload(
                new TenantId("integration-tenant"), "test-hr", OffsetDateTime.now().plusMinutes(1),
                2L, null, multipart(csv));

        assertEquals("FAILED", run.state());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM employees WHERE tenant_id = ? AND employee_id = ?",
                Integer.class, "integration-tenant", employeeId));
    }

    @Test
    void tenantSpecificThresholdControlsTheSafetyBrake() {
        String includedEmployee = "EMP-" + UUID.randomUUID();
        String absentEmployee = "EMP-" + UUID.randomUUID();
        result(event(includedEmployee, 1, "BLR01", "DAY", "CREATE"));
        result(event(absentEmployee, 1, "BLR01", "DAY", "CREATE"));
        tenantSettingsService.updateSafetyThreshold("integration-tenant", java.math.BigDecimal.valueOf(60));
        String csv = """
                employeeId,sourceVersion,name,email,phone,officeCode,shift
                %s,1,Example Worker,worker@example.invalid,+10000000000,BLR01,DAY
                """.formatted(includedEmployee);

        SnapshotRunResponse run = snapshotService.upload(
                new TenantId("integration-tenant"), "test-hr", OffsetDateTime.now().plusMinutes(1),
                null, null, multipart(csv));

        assertEquals("COMPLETED", run.state());
        assertEquals(1L, run.missingEmployeeCount());
        assertEquals(new java.math.BigDecimal("50.000"), run.missingPercentage());
        assertEquals(false, jdbcTemplate.queryForObject(
                "SELECT active FROM employees WHERE tenant_id = ? AND employee_id = ?",
                Boolean.class, "integration-tenant", absentEmployee));
    }

    @Test
    void applyingRunResumesAfterACommittedBatchWasInterrupted() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_snapshot_test_employee() RETURNS trigger AS $$
                BEGIN
                    IF NEW.employee_id LIKE 'FAIL-%' THEN
                        RAISE EXCEPTION 'injected snapshot failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_snapshot_test_employee_trigger
                BEFORE INSERT OR UPDATE ON employees
                FOR EACH ROW EXECUTE FUNCTION fail_snapshot_test_employee()
                """);
        String employeeId = "FAIL-" + UUID.randomUUID();
        String csv = """
                employeeId,sourceVersion,name,email,phone,officeCode,shift
                %s,1,Example Worker,worker@example.invalid,+10000000000,BLR01,DAY
                """.formatted(employeeId);
        try {
            assertThrows(DataAccessException.class, () -> snapshotService.upload(
                    new TenantId("integration-tenant"), "test-hr", OffsetDateTime.now().plusMinutes(1),
                    null, null, multipart(csv)));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER fail_snapshot_test_employee_trigger ON employees");
            jdbcTemplate.execute("DROP FUNCTION fail_snapshot_test_employee()");
        }

        UUID runId = jdbcTemplate.queryForObject(
                """
                SELECT run_id FROM snapshot_runs
                WHERE tenant_id = ? AND source_system = 'test-hr' AND state = 'APPLYING'
                ORDER BY created_at DESC LIMIT 1
                """,
                UUID.class, "integration-tenant");
        snapshotService.resumeApplyingRuns();

        assertEquals("COMPLETED", jdbcTemplate.queryForObject(
                "SELECT state FROM snapshot_runs WHERE run_id = ?", String.class, runId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND employee_id = ?",
                Integer.class, "integration-tenant", employeeId));
    }

    private List<com.moveinsync.roster.dto.EventProcessingResult> result(HrEventRequest event) {
        return hrEventService.process(new TenantId("integration-tenant"), List.of(event));
    }

    private HrEventRequest event(String employeeId, long version, String office, String shift, String type) {
        return new HrEventRequest(null, employeeId, "test-hr", version, type,
                Map.of("name", "Example Worker", "email", "worker@example.invalid",
                        "phone", "+10000000000", "officeCode", office, "shift", shift));
    }

    private String processAfterStart(
            HrEventRequest event, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test did not start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test was interrupted", exception);
        }
        return result(event).get(0).status();
    }

    private int countOutbox(String employeeId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE employee_id = ?", Integer.class, employeeId);
    }

    private MockMultipartFile multipart(String csv) {
        return new MockMultipartFile("file", "snapshot.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }
}
