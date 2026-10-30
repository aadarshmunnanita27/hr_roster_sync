package com.moveinsync.roster.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.core.EmployeeState;
import com.moveinsync.roster.core.EventType;
import com.moveinsync.roster.core.HrFingerprintService;
import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.dto.EventProcessingResult;
import com.moveinsync.roster.messaging.AbstractDomainEvent;
import com.moveinsync.roster.messaging.EmployeeCreatedEvent;
import com.moveinsync.roster.messaging.EmployeeDeactivatedEvent;
import com.moveinsync.roster.messaging.EmployeeUpdatedEvent;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeSyncService {
    private static final RowMapper<StoredEmployee> EMPLOYEE_MAPPER = (result, rowNumber) ->
            new StoredEmployee(
                    result.getLong("hr_version"),
                    result.getString("hr_hash"),
                    new EmployeeState(
                            result.getString("name"),
                            result.getString("email"),
                            result.getString("phone"),
                            result.getString("manager_id"),
                            result.getString("office_code"),
                            result.getString("shift"),
                                    result.getBoolean("active")),
                            result.getString("hr_source_system"),
                            result.getObject("hr_updated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;
    private final HrFingerprintService fingerprintService;
    private final ObjectMapper objectMapper;

    public EmployeeSyncService(
            JdbcTemplate jdbcTemplate, HrFingerprintService fingerprintService, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.fingerprintService = fingerprintService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public EventProcessingResult process(TenantId tenantId, ValidatedHrEvent event) {
        String employeeId = event.employeeId().value();
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Object.class,
                tenantId.value() + ":" + employeeId);

        StoredEmployee existing = jdbcTemplate.query(
                        """
                        SELECT hr_version, hr_hash, name, email, phone, manager_id, office_code, shift, active,
                               hr_source_system,
                               hr_updated_at
                        FROM employees
                        WHERE tenant_id = ? AND employee_id = ?
                        FOR UPDATE
                        """,
                        EMPLOYEE_MAPPER,
                        tenantId.value(),
                        employeeId)
                .stream()
                .findFirst()
                .orElse(null);

        if (event.eventType() == EventType.DEACTIVATE && existing == null) {
            return EventProcessingResult.applied(employeeId, "STALE_IGNORED");
        }
        if (existing != null && !"unknown".equals(existing.sourceSystem())
                && !existing.sourceSystem().equals(event.sourceSystem())) {
            return EventProcessingResult.rejected(employeeId, "SOURCE_SYSTEM_CONFLICT", "sourceSystem");
        }

        EmployeeState nextState = event.eventType() == EventType.DEACTIVATE
                ? withActive(existing.state(), false)
                : event.state();
        String nextFingerprint = event.eventType() == EventType.DEACTIVATE
                ? fingerprintService.fingerprint(nextState).value()
                : event.fingerprint();

        if (existing != null) {
            if (event.sourceVersion().value() < existing.version()) {
                return EventProcessingResult.applied(employeeId, "STALE_IGNORED");
            }
            if (event.sourceVersion().value() == existing.version()) {
                return EventProcessingResult.applied(employeeId,
                        existing.fingerprint().equals(nextFingerprint) ? "DUPLICATE" : "VERSION_CONFLICT");
            }
        }

        jdbcTemplate.update(
                """
                INSERT INTO hr_event_inbox (tenant_id, source_system, employee_id, source_version, fingerprint)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                tenantId.value(), event.sourceSystem(), employeeId, event.sourceVersion().value(), nextFingerprint);

        if (existing == null) {
            jdbcTemplate.update(
                    """
                    INSERT INTO employees
                        (tenant_id, employee_id, name, email, phone, manager_id, office_code, shift,
                         active, hr_version, hr_hash, hr_source_system, hr_updated_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                    """,
                    tenantId.value(), employeeId, nextState.name(), nextState.email(), nextState.phone(),
                    nextState.managerId(), nextState.officeCode(), nextState.shift(), nextState.active(),
                    event.sourceVersion().value(), nextFingerprint, event.sourceSystem());
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE employees
                    SET name = ?, email = ?, phone = ?, manager_id = ?, office_code = ?, shift = ?,
                        active = ?, hr_version = ?, hr_hash = ?, hr_source_system = ?,
                        hr_updated_at = now(), updated_at = now()
                    WHERE tenant_id = ? AND employee_id = ?
                    """,
                    nextState.name(), nextState.email(), nextState.phone(), nextState.managerId(),
                    nextState.officeCode(), nextState.shift(), nextState.active(),
                    event.sourceVersion().value(), nextFingerprint, event.sourceSystem(),
                    tenantId.value(), employeeId);
        }

        String eventName = nextState.active()
                ? existing == null ? "EMPLOYEE_CREATED" : "EMPLOYEE_UPDATED"
                : "EMPLOYEE_DEACTIVATED";
        insertOutboxEvent(tenantId, employeeId, eventName, event.sourceVersion().value());
        return EventProcessingResult.applied(employeeId, "APPLIED");
    }

    private EmployeeState withActive(EmployeeState current, boolean active) {
        return new EmployeeState(current.name(), current.email(), current.phone(),
                current.managerId(), current.officeCode(), current.shift(), active);
    }

    private void insertOutboxEvent(TenantId tenantId, String employeeId, String eventType, long version) {
        UUID eventId = UUID.randomUUID();
        String payload;
        try {
            AbstractDomainEvent event = switch (eventType) {
                case "EMPLOYEE_CREATED" ->
                        new EmployeeCreatedEvent(eventId, tenantId.value(), employeeId, version);
                case "EMPLOYEE_UPDATED" ->
                        new EmployeeUpdatedEvent(eventId, tenantId.value(), employeeId, version);
                case "EMPLOYEE_DEACTIVATED" ->
                        new EmployeeDeactivatedEvent(eventId, tenantId.value(), employeeId, version);
                default -> throw new IllegalArgumentException("Unsupported employee event");
            };
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode an outbox event", exception);
        }

        jdbcTemplate.update(
                """
                INSERT INTO outbox_events (event_id, tenant_id, employee_id, event_type, source_version, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """,
                eventId, tenantId.value(), employeeId, eventType, version, payload);
    }

    @Transactional
    public boolean deactivateMissing(
            TenantId tenantId,
            String sourceSystem,
            String employeeId,
            long expectedVersion,
            OffsetDateTime snapshotCutoff,
            UUID runId) {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Object.class,
                tenantId.value() + ":" + employeeId);

        StoredEmployee existing = jdbcTemplate.query(
                        """
                        SELECT hr_version, hr_hash, name, email, phone, manager_id, office_code, shift, active,
                               hr_source_system,
                               hr_updated_at
                        FROM employees
                        WHERE tenant_id = ? AND employee_id = ?
                        FOR UPDATE
                        """,
                        EMPLOYEE_MAPPER,
                        tenantId.value(),
                        employeeId)
                .stream()
                .findFirst()
                .orElse(null);
        if (existing == null || !existing.state().active()
                || !existing.sourceSystem().equals(sourceSystem)
                || existing.version() != expectedVersion
                || existing.hrUpdatedAt().isAfter(snapshotCutoff)) {
            return false;
        }

        EmployeeState inactive = withActive(existing.state(), false);
        long deactivationVersion = existing.version() + 1;
        String fingerprint = fingerprintService.fingerprint(inactive).value();
        int updated = jdbcTemplate.update(
                """
                UPDATE employees
                SET active = false, hr_version = ?, hr_hash = ?, hr_updated_at = now(), updated_at = now()
                WHERE tenant_id = ? AND employee_id = ? AND hr_version = ? AND active
                """,
                deactivationVersion, fingerprint, tenantId.value(), employeeId, expectedVersion);
        if (updated == 0) {
            return false;
        }
        jdbcTemplate.update(
                """
                INSERT INTO hr_event_inbox (tenant_id, source_system, employee_id, source_version, fingerprint)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                tenantId.value(), "snapshot:" + runId, employeeId, deactivationVersion, fingerprint);
        insertOutboxEvent(tenantId, employeeId, "EMPLOYEE_DEACTIVATED", deactivationVersion);
        return true;
    }

    private record StoredEmployee(
        long version, String fingerprint, EmployeeState state, String sourceSystem,
        OffsetDateTime hrUpdatedAt) {
    }
}
