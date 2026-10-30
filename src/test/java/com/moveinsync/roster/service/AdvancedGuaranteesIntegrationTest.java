package com.moveinsync.roster.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.dto.EventProcessingResult;
import com.moveinsync.roster.dto.HrEventRequest;
import com.moveinsync.roster.exception.ResourceNotFoundException;
import com.moveinsync.roster.exception.RosterException;
import com.moveinsync.roster.exception.TenantAccessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Advanced-track guarantees that sit on top of {@link RosterIntegrationTest}:
 * more concurrency tests, failure-injection tests (broker outage, mid-transaction
 * crash, interrupted upload), tenant isolation at the data layer and a planted-PII scan.
 *
 * Requires Docker (Testcontainers). Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "roster.outbox.initial-delay=3600000",
                "roster.outbox.poll-interval=3600000"
        })
class AdvancedGuaranteesIntegrationTest {
    private static final String TENANT = "adv-tenant";
    private static final String OTHER_TENANT = "adv-other-tenant";
    private static final String FAIL_TENANT = "adv-fail-tenant";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

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
    private SnapshotService snapshotService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private ObjectMapper objectMapper;

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
    void addOffices() {
        for (String tenant : List.of(TENANT, OTHER_TENANT, FAIL_TENANT)) {
            for (String office : List.of("BLR01", "BLR02")) {
                jdbcTemplate.update(
                        """
                        INSERT INTO offices (tenant_id, office_code, office_name) VALUES (?, ?, ?)
                        ON CONFLICT (tenant_id, office_code) DO NOTHING
                        """,
                        tenant, office, "Advanced test office");
            }
        }
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    void identicalEventsRacingProduceExactlyOneApplyAndOneOutboxRow() throws Exception {
        String employeeId = "EMP-" + UUID.randomUUID();
        HrEventRequest event = event(employeeId, 1, "BLR01", "DAY", "CREATE");

        List<String> statuses = runConcurrently(8, () -> result(TENANT, event).get(0).status());

        assertEquals(1, statuses.stream().filter("APPLIED"::equals).count(), statuses.toString());
        assertEquals(7, statuses.stream().filter("DUPLICATE"::equals).count(), statuses.toString());
        assertEquals(1, countOutbox(TENANT, employeeId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM employees WHERE tenant_id = ? AND employee_id = ?",
                Integer.class, TENANT, employeeId));
    }

    @Test
    void versionsRacingInAnyOrderAlwaysLeaveTheHighestVersionAndOneOutboxRowPerApply() throws Exception {
        for (int round = 0; round < 10; round++) {
            String employeeId = "EMP-" + UUID.randomUUID();
            List<Integer> versions = new ArrayList<>(List.of(1, 2, 3, 4, 5));
            Collections.shuffle(versions);
            List<Callable<String>> tasks = new ArrayList<>();
            for (int version : versions) {
                tasks.add(() -> result(TENANT, event(employeeId, version, "BLR01",
                        "SHIFT-" + version, "UPDATE")).get(0).status());
            }
            List<String> statuses = runAll(tasks);

            long finalVersion = jdbcTemplate.queryForObject(
                    "SELECT hr_version FROM employees WHERE tenant_id = ? AND employee_id = ?",
                    Long.class, TENANT, employeeId);
            String shift = jdbcTemplate.queryForObject(
                    "SELECT shift FROM employees WHERE tenant_id = ? AND employee_id = ?",
                    String.class, TENANT, employeeId);
            assertEquals(5L, finalVersion, "round " + round + " statuses " + statuses);
            assertEquals("SHIFT-5", shift);
            assertEquals(statuses.stream().filter("APPLIED"::equals).count(),
                    countOutbox(TENANT, employeeId),
                    "every applied event writes exactly one outbox row; stale ones write none");
        }
    }

    @Test
    void duplicateDeliveriesRacingReleaseTheSeatAndWriteTheLedgerOnce() throws Exception {
        String employeeId = "EMP-" + UUID.randomUUID();
        result(TENANT, event(employeeId, 1, "BLR01", "DAY", "CREATE"));
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        insertBookingWithSeat(TENANT, employeeId, bookingId, seatId);
        UUID eventId = UUID.randomUUID();

        runConcurrently(8, () -> {
            bookingService.cancelFutureConfirmed(TENANT, employeeId, eventId);
            return "done";
        });

        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE booking_id = ?", String.class, bookingId));
        assertEquals(false, jdbcTemplate.queryForObject(
                "SELECT occupied FROM cab_seats WHERE seat_id = ?", Boolean.class, seatId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE message_id = ?", Integer.class, eventId));
    }

    // ---------------------------------------------------------------- failure injection

    @Test
    void failureMidCancellationRollsBackTheLedgerSoRedeliveryCompletesTheWork() {
        String employeeId = "EMP-" + UUID.randomUUID();
        result(FAIL_TENANT, event(employeeId, 1, "BLR01", "DAY", "CREATE"));
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        insertBookingWithSeat(FAIL_TENANT, employeeId, bookingId, seatId);
        UUID eventId = UUID.randomUUID();

        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_seat_release() RETURNS trigger AS $$
                BEGIN
                    IF OLD.tenant_id = 'adv-fail-tenant' THEN
                        RAISE EXCEPTION 'injected seat release failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_seat_release_trigger
                BEFORE UPDATE ON cab_seats
                FOR EACH ROW EXECUTE FUNCTION fail_seat_release()
                """);
        try {
            assertThrows(DataAccessException.class,
                    () -> bookingService.cancelFutureConfirmed(FAIL_TENANT, employeeId, eventId));

            // Nothing partial is visible: booking untouched, seat still held, ledger empty.
            assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
                    "SELECT status FROM bookings WHERE booking_id = ?", String.class, bookingId));
            assertEquals(true, jdbcTemplate.queryForObject(
                    "SELECT occupied FROM cab_seats WHERE seat_id = ?", Boolean.class, seatId));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM processed_messages WHERE message_id = ?", Integer.class, eventId));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER fail_seat_release_trigger ON cab_seats");
            jdbcTemplate.execute("DROP FUNCTION fail_seat_release()");
        }

        // Broker redelivers the same message: it now succeeds exactly once.
        bookingService.cancelFutureConfirmed(FAIL_TENANT, employeeId, eventId);
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE booking_id = ?", String.class, bookingId));
        assertEquals(false, jdbcTemplate.queryForObject(
                "SELECT occupied FROM cab_seats WHERE seat_id = ?", Boolean.class, seatId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE message_id = ?", Integer.class, eventId));
    }

    @Test
    void brokerOutageKeepsEventsPendingWithBackoffAndTheyDrainAfterRecovery() {
        // Isolate this test from rows left pending by other tests.
        jdbcTemplate.update("UPDATE outbox_events SET status = 'PUBLISHED' WHERE status = 'PENDING'");
        String employeeId = "EMP-" + UUID.randomUUID();
        result(TENANT, event(employeeId, 1, "BLR01", "DAY", "CREATE"));

        var docker = RABBITMQ.getDockerClient();
        docker.pauseContainerCmd(RABBITMQ.getContainerId()).exec();
        boolean paused = true;
        try {
            outboxService.publishPending();

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT status, attempts, last_error_code, next_attempt_at > now() AS backed_off "
                            + "FROM outbox_events WHERE tenant_id = ? AND employee_id = ?",
                    TENANT, employeeId);
            assertEquals("PENDING", row.get("status"), "the event must not be lost or marked published");
            assertTrue(((Number) row.get("attempts")).intValue() >= 1);
            assertEquals("BROKER_PUBLISH_FAILED", row.get("last_error_code"));
            assertEquals(true, row.get("backed_off"));
        } finally {
            if (paused) {
                docker.unpauseContainerCmd(RABBITMQ.getContainerId()).exec();
            }
        }

        // Broker is back: the publisher drains the backlog on its own schedule (we force "due" here).
        Awaitility.await().atMost(60, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).untilAsserted(() -> {
            jdbcTemplate.update(
                    "UPDATE outbox_events SET next_attempt_at = now() WHERE status = 'PENDING' "
                            + "AND tenant_id = ? AND employee_id = ?", TENANT, employeeId);
            outboxService.publishPending();
            assertEquals("PUBLISHED", jdbcTemplate.queryForObject(
                    "SELECT status FROM outbox_events WHERE tenant_id = ? AND employee_id = ?",
                    String.class, TENANT, employeeId));
        });
    }

    @Test
    void uploadInterruptedMidStreamFailsTheRunAndAppliesNothing() throws Exception {
        String employeeId = "EMP-" + UUID.randomUUID();
        String partial = "employeeId,sourceVersion,name,email,phone,officeCode,shift\n"
                + employeeId + ",1,Example Worker,worker@example.invalid,+10000000000,BLR01,DAY\n";
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getInputStream()).thenReturn(new FailingAfterPrefixStream(
                partial.getBytes(StandardCharsets.UTF_8)));

        assertThrows(RuntimeException.class, () -> snapshotService.upload(
                new TenantId(TENANT), "interrupted-hr", OffsetDateTime.now().plusMinutes(1),
                null, null, file));

        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT state FROM snapshot_runs WHERE tenant_id = ? AND source_system = 'interrupted-hr' "
                        + "ORDER BY created_at DESC LIMIT 1", String.class, TENANT));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM employees WHERE tenant_id = ? AND employee_id = ?",
                Integer.class, TENANT, employeeId));
    }

    // ---------------------------------------------------------------- tenant isolation

    @Test
    void anEventCarryingAnotherTenantIdIsRejectedAndWritesNothing() {
        String employeeId = "EMP-" + UUID.randomUUID();
        HrEventRequest spoofed = new HrEventRequest(OTHER_TENANT, employeeId, "test-hr", 1L, "CREATE",
                event(employeeId, 1, "BLR01", "DAY", "CREATE").data());

        assertThrows(TenantAccessException.class,
                () -> hrEventService.process(new TenantId(TENANT), List.of(spoofed)));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM employees WHERE employee_id = ?", Integer.class, employeeId));
        assertEquals(0, countOutbox(TENANT, employeeId) + countOutbox(OTHER_TENANT, employeeId));
    }

    @Test
    void employeesAndSnapshotRunsAreInvisibleAcrossTenants() {
        String employeeId = "EMP-" + UUID.randomUUID();
        result(TENANT, event(employeeId, 1, "BLR01", "DAY", "CREATE"));

        assertThrows(ResourceNotFoundException.class,
                () -> employeeService.getEmployee(OTHER_TENANT, employeeId));
        assertEquals(employeeId, employeeService.getEmployee(TENANT, employeeId).employeeId());

        var run = snapshotService.upload(new TenantId(TENANT), "test-hr",
                OffsetDateTime.now().plusMinutes(1), null, null, multipart(
                        "employeeId,sourceVersion,name,email,phone,officeCode,shift\n"
                                + employeeId + ",1,Example Worker,worker@example.invalid,"
                                + "+10000000000,BLR01,DAY\n"));
        assertThrows(ResourceNotFoundException.class,
                () -> snapshotService.getRun(new TenantId(OTHER_TENANT), run.runId()));
        assertThrows(ResourceNotFoundException.class,
                () -> snapshotService.getErrors(new TenantId(OTHER_TENANT), run.runId()));
    }

    @Test
    void anotherTenantsEmptySnapshotCannotDeactivateThisTenantsEmployees() {
        String employeeId = "EMP-" + UUID.randomUUID();
        result(TENANT, event(employeeId, 1, "BLR01", "DAY", "CREATE"));

        snapshotService.upload(new TenantId(OTHER_TENANT), "test-hr", OffsetDateTime.now().plusMinutes(1),
                null, null, multipart("employeeId,sourceVersion,name,email,phone,officeCode,shift\n"));

        assertEquals(true, jdbcTemplate.queryForObject(
                "SELECT active FROM employees WHERE tenant_id = ? AND employee_id = ?",
                Boolean.class, TENANT, employeeId));
    }

    // ---------------------------------------------------------------- privacy

    @Test
    void plantedPersonalDataNeverAppearsInResultsErrorsLogsOrOutboxPayloads() throws Exception {
        String badEmail = "planted-pii-email-without-at-sign";
        String badPhone = "PLANTED-PHONE-ABC";
        String plantedName = "Zaphod Plantedname";
        List<String> planted = List.of(badEmail, badPhone, plantedName, "Plantedname", "planted-pii");

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        List<String> observable = new ArrayList<>();
        String applied = "EMP-" + UUID.randomUUID();
        try {
            List<EventProcessingResult> results = new ArrayList<>();
            results.addAll(hrEventService.process(new TenantId(TENANT), List.of(
                    new HrEventRequest(null, "EMP-" + UUID.randomUUID(), "test-hr", 1L, "CREATE",
                            Map.of("name", plantedName, "email", badEmail, "phone", "+10000000000",
                                    "officeCode", "BLR01", "shift", "DAY")),
                    new HrEventRequest(null, "EMP-" + UUID.randomUUID(), "test-hr", 1L, "CREATE",
                            Map.of("name", plantedName, "email", "ok@example.invalid", "phone", badPhone,
                                    "officeCode", "BLR01", "shift", "DAY")),
                    new HrEventRequest(null, applied, "test-hr", 1L, "CREATE",
                            Map.of("name", plantedName, "email", "ok@example.invalid",
                                    "phone", "+10000000000", "officeCode", "BLR01", "shift", "DAY")))));
            for (EventProcessingResult result : results) {
                observable.add(objectMapper.writeValueAsString(result));
            }

            String csv = "employeeId,sourceVersion,name,email,phone,officeCode,shift\n"
                    + "EMP-" + UUID.randomUUID() + ",1," + plantedName + "," + badEmail
                    + ",+10000000000,BLR01,DAY\n"
                    + "EMP-" + UUID.randomUUID() + ",1," + plantedName + ",ok@example.invalid,"
                    + badPhone + ",BLR01,DAY\n";
            var run = snapshotService.upload(new TenantId(TENANT), "pii-hr",
                    OffsetDateTime.now().plusMinutes(1), null, null, multipart(csv));
            observable.add(objectMapper.writeValueAsString(run));
            observable.add(objectMapper.writeValueAsString(
                    snapshotService.getErrors(new TenantId(TENANT), run.runId())));
            observable.addAll(jdbcTemplate.queryForList(
                    "SELECT concat_ws('|', employee_id, error_code) FROM snapshot_errors WHERE run_id = ?",
                    String.class, run.runId()));
        } finally {
            root.detachAppender(appender);
        }

        for (ILoggingEvent logEvent : appender.list) {
            observable.add(logEvent.getFormattedMessage());
            observable.add(String.valueOf(logEvent.getMDCPropertyMap()));
            if (logEvent.getThrowableProxy() != null) {
                observable.add(logEvent.getThrowableProxy().getMessage());
            }
        }
        observable.addAll(jdbcTemplate.queryForList(
                "SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND employee_id = ?",
                String.class, TENANT, applied));

        assertFalse(observable.isEmpty());
        for (String text : observable) {
            for (String secret : planted) {
                assertFalse(text != null && text.toLowerCase().contains(secret.toLowerCase()),
                        "personal data leaked into an observable output: " + secret);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private List<EventProcessingResult> result(String tenant, HrEventRequest event) {
        return hrEventService.process(new TenantId(tenant), List.of(event));
    }

    private HrEventRequest event(String employeeId, long version, String office, String shift, String type) {
        return new HrEventRequest(null, employeeId, "test-hr", version, type,
                Map.of("name", "Example Worker", "email", "worker@example.invalid",
                        "phone", "+10000000000", "officeCode", office, "shift", shift));
    }

    private int countOutbox(String tenant, String employeeId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND employee_id = ?",
                Integer.class, tenant, employeeId);
    }

    private void insertBookingWithSeat(String tenant, String employeeId, UUID bookingId, UUID seatId) {
        jdbcTemplate.update(
                """
                INSERT INTO bookings (booking_id, tenant_id, employee_id, seat_id, status, scheduled_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', now() + interval '1 day')
                """,
                bookingId, tenant, employeeId, seatId);
        jdbcTemplate.update(
                "INSERT INTO cab_seats (seat_id, tenant_id, booking_id, occupied) VALUES (?, ?, ?, true)",
                seatId, tenant, bookingId);
    }

    private MockMultipartFile multipart(String csv) {
        return new MockMultipartFile("file", "snapshot.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    /** Runs the same task on {@code threads} threads released at the same instant. */
    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(task);
        }
        return runAll(tasks);
    }

    private <T> List<T> runAll(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> values = new ArrayList<>();
            for (Future<T> future : futures) {
                values.add(future.get(30, TimeUnit.SECONDS));
            }
            return values;
        } finally {
            executor.shutdownNow();
        }
    }

    /** Serves a CSV prefix and then fails like a dropped client connection. */
    private static final class FailingAfterPrefixStream extends InputStream {
        private final byte[] prefix;
        private int position;

        FailingAfterPrefixStream(byte[] prefix) {
            this.prefix = prefix;
        }

        @Override
        public int read() throws IOException {
            if (position < prefix.length) {
                return prefix[position++] & 0xff;
            }
            throw new IOException("simulated client disconnect");
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (position >= prefix.length) {
                throw new IOException("simulated client disconnect");
            }
            int count = Math.min(length, prefix.length - position);
            System.arraycopy(prefix, position, buffer, offset, count);
            position += count;
            return count;
        }
    }
}
