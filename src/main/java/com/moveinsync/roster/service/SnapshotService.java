package com.moveinsync.roster.service;

import com.moveinsync.roster.core.EmployeeId;
import com.moveinsync.roster.core.EmployeeState;
import com.moveinsync.roster.core.EventType;
import com.moveinsync.roster.core.SafetyBrake;
import com.moveinsync.roster.core.SourceVersion;
import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.dto.HrEventRequest;
import com.moveinsync.roster.dto.SnapshotErrorResponse;
import com.moveinsync.roster.dto.SnapshotRunResponse;
import com.moveinsync.roster.exception.ResourceNotFoundException;
import com.moveinsync.roster.exception.RosterException;
import com.moveinsync.roster.exception.TenantAccessException;
import com.moveinsync.roster.exception.ValidationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class SnapshotService {
    private static final Logger logger = LoggerFactory.getLogger(SnapshotService.class);
    private static final String REQUIRED_HEADER =
            "employeeId,sourceVersion,name,email,phone,officeCode,shift";

    private final JdbcTemplate jdbcTemplate;
    private final HrEventService hrEventService;
    private final EmployeeSyncService employeeSyncService;
    private final SnapshotApplyBatchService snapshotApplyBatchService;
    private final SnapshotParser snapshotParser;
    private final SafetyBrake safetyBrake;
    private final MeterRegistry meterRegistry;
    private final TenantSettingsService tenantSettingsService;
    private final SnapshotDecisionService decisionService;
    private final int batchSize;
    private final AtomicLong latestMissingEmployeeCount = new AtomicLong();

    public SnapshotService(
            JdbcTemplate jdbcTemplate,
            HrEventService hrEventService,
            EmployeeSyncService employeeSyncService,
            SnapshotApplyBatchService snapshotApplyBatchService,
            SnapshotParser snapshotParser,
            TenantSettingsService tenantSettingsService,
            SnapshotDecisionService decisionService,
            SafetyBrake safetyBrake,
            MeterRegistry meterRegistry,
            @Value("${roster.reconciliation.batch-size:2000}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.hrEventService = hrEventService;
        this.employeeSyncService = employeeSyncService;
        this.snapshotApplyBatchService = snapshotApplyBatchService;
        this.snapshotParser = snapshotParser;
        this.tenantSettingsService = tenantSettingsService;
        this.decisionService = decisionService;
        this.safetyBrake = safetyBrake;
        this.meterRegistry = meterRegistry;
        this.batchSize = batchSize;
        meterRegistry.gauge("reconciliation_missing_employees",
                latestMissingEmployeeCount, AtomicLong::doubleValue);
    }

    public SnapshotRunResponse upload(
            TenantId tenantId,
            String sourceSystem,
            OffsetDateTime cutoff,
            Long expectedRowCount,
            String expectedChecksum,
            MultipartFile file) {
        if (sourceSystem == null || sourceSystem.isBlank() || sourceSystem.length() > 100
                || cutoff == null || file == null || file.isEmpty()) {
            throw new ValidationException("INVALID_SNAPSHOT_UPLOAD", null);
        }
        if (expectedRowCount != null && expectedRowCount < 0) {
            throw new ValidationException("INVALID_MANIFEST", "expectedRowCount");
        }
        if (expectedChecksum != null && !expectedChecksum.matches("[a-fA-F0-9]{64}")) {
            throw new ValidationException("INVALID_MANIFEST", "expectedChecksum");
        }

        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO snapshot_runs
                    (run_id, tenant_id, source_system, snapshot_cutoff, expected_row_count,
                     expected_checksum, state, safety_threshold_percent)
                VALUES (?, ?, ?, ?, ?, ?, 'CREATED', ?)
                """,
                runId, tenantId.value(), sourceSystem, cutoff, expectedRowCount,
                expectedChecksum == null ? null : expectedChecksum.toLowerCase(),
                tenantSettingsService.safetyThreshold(tenantId.value()));
        jdbcTemplate.update(
                "UPDATE snapshot_runs SET state = 'UPLOADING', updated_at = now() WHERE run_id = ?",
                runId);

        long uploadStarted = System.nanoTime();
        try {
            UploadSummary summary = stageFile(runId, tenantId, sourceSystem, file);
            jdbcTemplate.update(
                    """
                    UPDATE snapshot_runs SET uploaded_row_count = ?, uploaded_checksum = ?,
                        state = 'VALIDATING', updated_at = now() WHERE run_id = ?
                    """,
                    summary.rowCount(), summary.checksum(), runId);
            if (expectedRowCount != null && expectedRowCount != summary.rowCount()) {
                markFailed(runId, summary, "ROW_COUNT_MISMATCH");
                return getRun(tenantId, runId);
            }
            if (expectedChecksum != null && !expectedChecksum.equalsIgnoreCase(summary.checksum())) {
                markFailed(runId, summary, "CHECKSUM_MISMATCH");
                return getRun(tenantId, runId);
            }

            jdbcTemplate.update(
                    "UPDATE snapshot_runs SET state = 'DIFFING', updated_at = now() WHERE run_id = ?",
                    runId);
            calculateDiffAndApplyIfSafe(tenantId, runId, cutoff, sourceSystem);
            meterRegistry.timer("reconciliation_duration").record(
                    System.nanoTime() - uploadStarted, TimeUnit.NANOSECONDS);
            return getRun(tenantId, runId);
        } catch (IOException exception) {
            markFailed(runId, new UploadSummary(0, ""), "UPLOAD_READ_FAILED");
            throw new RosterException("UPLOAD_READ_FAILED", null, 400);
        } catch (RuntimeException exception) {
            if (!"APPLYING".equals(readState(runId))) {
                jdbcTemplate.update(
                        "UPDATE snapshot_runs SET state = 'FAILED', updated_at = now() WHERE run_id = ?",
                        runId);
            }
            throw exception;
        }
    }

    public SnapshotRunResponse getRun(TenantId tenantId, UUID runId) {
        return jdbcTemplate.query(
                        """
                        SELECT run_id, state, uploaded_row_count, active_employee_count,
                               snapshot_employee_count, unchanged_employee_count, updated_employee_count,
                               new_employee_count, stale_employee_count, missing_employee_count, missing_percentage,
                               safety_threshold_percent, created_at
                        FROM snapshot_runs WHERE tenant_id = ? AND run_id = ?
                        """,
                        (result, rowNumber) -> new SnapshotRunResponse(
                                result.getObject("run_id", UUID.class),
                                result.getString("state"),
                                result.getLong("uploaded_row_count"),
                                nullableLong(result, "active_employee_count"),
                                nullableLong(result, "snapshot_employee_count"),
                                nullableLong(result, "unchanged_employee_count"),
                                nullableLong(result, "updated_employee_count"),
                                nullableLong(result, "new_employee_count"),
                                nullableLong(result, "stale_employee_count"),
                                nullableLong(result, "missing_employee_count"),
                                result.getBigDecimal("missing_percentage"),
                                result.getBigDecimal("safety_threshold_percent"),
                                result.getObject("created_at", OffsetDateTime.class)),
                        tenantId.value(), runId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("SNAPSHOT_NOT_FOUND"));
    }

    public List<SnapshotErrorResponse> getErrors(TenantId tenantId, UUID runId) {
        ensureRunExists(tenantId, runId);
        return jdbcTemplate.query(
                """
                SELECT employee_id, error_code FROM snapshot_errors
                WHERE run_id = ? ORDER BY error_id
                """,
                (result, rowNumber) -> new SnapshotErrorResponse(
                        result.getString("employee_id"), result.getString("error_code")),
                runId);
    }

    public SnapshotRunResponse approve(
            TenantId tenantId, UUID runId, String operatorId, String reason) {
        decide(tenantId, runId, operatorId, reason, "APPROVE");
        resumeRun(runId);
        return getRun(tenantId, runId);
    }

    public SnapshotRunResponse reject(
            TenantId tenantId, UUID runId, String operatorId, String reason) {
        decide(tenantId, runId, operatorId, reason, "REJECT");
        return getRun(tenantId, runId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeApplyingRuns() {
        List<UUID> runIds = jdbcTemplate.query(
                "SELECT run_id FROM snapshot_runs WHERE state = 'APPLYING' ORDER BY created_at",
                (result, rowNumber) -> result.getObject("run_id", UUID.class));
        for (UUID runId : runIds) {
            try {
                resumeRun(runId);
            } catch (RuntimeException exception) {
                logger.error("snapshot_resume_failed runId={} errorCode=SNAPSHOT_RESUME_FAILED", runId);
            }
        }
    }

    private UploadSummary stageFile(
            UUID runId, TenantId tenantId, String sourceSystem, MultipartFile file) throws IOException {
        MessageDigest digest = sha256();
        long rowCount = 0;
        List<StagedEmployee> rows = new ArrayList<>(batchSize);
        List<SnapshotError> errors = new ArrayList<>(batchSize);
        try (InputStream input = new DigestInputStream(file.getInputStream(), digest);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
             CSVParser parser = snapshotParser.parse(reader)) {
            if (!parser.getHeaderMap().keySet().containsAll(List.of(REQUIRED_HEADER.split(",")))) {
                throw new RosterException("INVALID_CSV_HEADER", null, 400);
            }
            if (parser.getHeaderMap().keySet().stream()
                    .anyMatch(header -> !Set.of("employeeId", "sourceVersion", "name", "email", "phone",
                            "managerId", "officeCode", "shift", "active").contains(header))) {
                throw new RosterException("INVALID_CSV_HEADER", null, 400);
            }

            for (CSVRecord record : parser) {
                rowCount++;
                String employeeId = safeField(record, "employeeId");
                try {
                    if (!record.isConsistent()) {
                        throw new ValidationException("INVALID_SNAPSHOT_ROW", null);
                    }
                    rows.add(toStagedEmployee(record, tenantId, sourceSystem));
                } catch (ValidationException | IllegalArgumentException exception) {
                    String errorCode = exception instanceof ValidationException validation
                            ? validation.errorCode() : "INVALID_SNAPSHOT_ROW";
                    errors.add(new SnapshotError(employeeId, errorCode));
                }

                if (rows.size() >= batchSize) {
                    persistRows(runId, tenantId, rows, errors);
                    rows.clear();
                    errors.clear();
                } else if (errors.size() >= batchSize) {
                    persistErrors(runId, errors);
                    errors.clear();
                }
            }
        }
        persistRows(runId, tenantId, rows, errors);
        String checksum = HexFormat.of().formatHex(digest.digest());
        return new UploadSummary(rowCount, checksum);
    }

    private StagedEmployee toStagedEmployee(CSVRecord record, TenantId tenantId, String sourceSystem) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", record.get("name"));
        data.put("email", record.get("email"));
        data.put("phone", record.get("phone"));
        data.put("officeCode", record.get("officeCode"));
        data.put("shift", record.get("shift"));
        if (record.isMapped("managerId")) {
            data.put("managerId", blankToNull(record.get("managerId")));
        }
        if (record.isMapped("active") && !record.get("active").isBlank()) {
            data.put("active", Boolean.parseBoolean(record.get("active")));
            if (!"true".equalsIgnoreCase(record.get("active"))
                    && !"false".equalsIgnoreCase(record.get("active"))) {
                throw new ValidationException("INVALID_FIELD", "active");
            }
        }

        long version;
        try {
            version = Long.parseLong(record.get("sourceVersion"));
        } catch (NumberFormatException exception) {
            throw new ValidationException("INVALID_SOURCE_VERSION", "sourceVersion");
        }
        HrEventRequest request = new HrEventRequest(null, record.get("employeeId"), sourceSystem,
                version, "UPDATE", data);
        var validated = hrEventService.validateForSnapshot(tenantId, request);
        EmployeeState state = validated.state();
        return new StagedEmployee(validated.employeeId().value(), version, state, validated.fingerprint());
    }

    private void persistRows(
            UUID runId, TenantId tenantId, List<StagedEmployee> rows, List<SnapshotError> errors) {
        if (!rows.isEmpty()) {
            int[][] counts = jdbcTemplate.batchUpdate(
                    """
                    INSERT INTO snapshot_staging
                        (run_id, tenant_id, employee_id, source_version, name, email, phone, manager_id,
                         office_code, shift, fingerprint, active)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (run_id, tenant_id, employee_id) DO NOTHING
                    """,
                    rows,
                    Math.min(rows.size(), batchSize),
                    (statement, row) -> {
                        statement.setObject(1, runId);
                        statement.setString(2, tenantId.value());
                        statement.setString(3, row.employeeId());
                        statement.setLong(4, row.version());
                        statement.setString(5, row.state().name());
                        statement.setString(6, row.state().email());
                        statement.setString(7, row.state().phone());
                        statement.setString(8, row.state().managerId());
                        statement.setString(9, row.state().officeCode());
                        statement.setString(10, row.state().shift());
                        statement.setString(11, row.fingerprint());
                        statement.setBoolean(12, row.state().active());
                    });
            for (int[] batch : counts) {
                for (int index = 0; index < batch.length; index++) {
                    if (batch[index] == 0) {
                        errors.add(new SnapshotError(rows.get(index).employeeId(), "DUPLICATE_EMPLOYEE_ID"));
                        jdbcTemplate.update(
                                """
                                UPDATE snapshot_staging SET valid = false
                                WHERE run_id = ? AND tenant_id = ? AND employee_id = ?
                                """,
                                runId, tenantId.value(), rows.get(index).employeeId());
                    }
                }
            }
        }
        persistErrors(runId, errors);
    }

    private void persistErrors(UUID runId, List<SnapshotError> errors) {
        if (errors.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO snapshot_errors (run_id, employee_id, error_code) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                errors,
                Math.min(errors.size(), batchSize),
                (statement, error) -> {
                    statement.setObject(1, runId);
                    statement.setString(2, error.employeeId());
                    statement.setString(3, error.errorCode());
                });
    }

    private void calculateDiffAndApplyIfSafe(
            TenantId tenantId, UUID runId, OffsetDateTime cutoff, String sourceSystem) {
        protectSourceConflicts(tenantId, runId, sourceSystem);
        long activeCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM employees
                WHERE tenant_id = ? AND hr_source_system = ? AND active
                """,
                Long.class, tenantId.value(), sourceSystem);
        long stagedCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM snapshot_staging
                WHERE run_id = ? AND tenant_id = ? AND valid
                """,
                Long.class, runId, tenantId.value());
        DiffCounts diffCounts = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FILTER (WHERE employee.employee_id IS NOT NULL
                                        AND staged.source_version >= employee.hr_version
                                        AND staged.fingerprint = employee.hr_hash) AS unchanged_count,
                       count(*) FILTER (WHERE employee.employee_id IS NOT NULL
                                        AND staged.source_version >= employee.hr_version
                                        AND staged.fingerprint <> employee.hr_hash) AS updated_count,
                       count(*) FILTER (WHERE employee.employee_id IS NULL) AS new_count,
                       count(*) FILTER (WHERE employee.employee_id IS NOT NULL
                                        AND staged.source_version < employee.hr_version) AS stale_count
                FROM snapshot_staging staged
                LEFT JOIN employees employee
                  ON employee.tenant_id = staged.tenant_id
                 AND employee.employee_id = staged.employee_id
                WHERE staged.run_id = ? AND staged.tenant_id = ? AND staged.valid
                """,
                (result, rowNumber) -> new DiffCounts(
                        result.getLong("unchanged_count"),
                        result.getLong("updated_count"),
                        result.getLong("new_count"),
                        result.getLong("stale_count")),
                runId, tenantId.value());
        long missingCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM employees employee
                WHERE employee.tenant_id = ? AND employee.hr_source_system = ? AND employee.active
                  AND employee.created_at <= ?
                  AND NOT EXISTS (
                      SELECT 1 FROM snapshot_staging staged
                      WHERE staged.run_id = ? AND staged.tenant_id = employee.tenant_id
                        AND staged.employee_id = employee.employee_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM snapshot_errors error_row
                      WHERE error_row.run_id = ? AND error_row.employee_id = employee.employee_id
                  )
                """,
                Long.class, tenantId.value(), sourceSystem, cutoff, runId, runId);
        SafetyBrake.Assessment assessment =
                safetyBrake.assess(activeCount, activeCount - missingCount,
                        tenantSettingsService.safetyThreshold(tenantId.value()));

        jdbcTemplate.update(
                """
                UPDATE snapshot_runs
                SET active_employee_count = ?, snapshot_employee_count = ?,
                    unchanged_employee_count = ?, updated_employee_count = ?, new_employee_count = ?,
                    stale_employee_count = ?, missing_employee_count = ?, missing_percentage = ?,
                    updated_at = now()
                WHERE run_id = ?
                """,
                activeCount, stagedCount, diffCounts.unchanged(), diffCounts.updated(), diffCounts.created(),
                diffCounts.stale(), missingCount, assessment.missingPercentage(), runId);
        latestMissingEmployeeCount.set(missingCount);
        if (assessment.approvalRequired()) {
            jdbcTemplate.update(
                    "UPDATE snapshot_runs SET state = 'APPROVAL_REQUIRED', updated_at = now() WHERE run_id = ?",
                    runId);
            meterRegistry.counter("reconciliation_safety_brake_total").increment();
            return;
        }
        jdbcTemplate.update(
                "UPDATE snapshot_runs SET state = 'APPLYING', updated_at = now() WHERE run_id = ?",
                runId);
        applyRun(tenantId, runId, cutoff, sourceSystem);
    }

    private void resumeRun(UUID runId) {
        RunDetails details = jdbcTemplate.query(
                        """
                        SELECT tenant_id, source_system, snapshot_cutoff
                        FROM snapshot_runs WHERE run_id = ? AND state IN ('APPROVED', 'APPLYING')
                        """,
                        (result, rowNumber) -> new RunDetails(
                                result.getString("tenant_id"),
                                result.getString("source_system"),
                                result.getObject("snapshot_cutoff", OffsetDateTime.class)),
                        runId)
                .stream()
                .findFirst()
                .orElse(null);
        if (details != null) {
            jdbcTemplate.update(
                    "UPDATE snapshot_runs SET state = 'APPLYING', updated_at = now() WHERE run_id = ?",
                    runId);
            applyRun(new TenantId(details.tenantId()), runId, details.cutoff(), details.sourceSystem());
        }
    }

    private void applyRun(TenantId tenantId, UUID runId, OffsetDateTime cutoff, String sourceSystem) {
        String lastEmployeeId = "";
        while (true) {
            List<StagedEmployee> rows = jdbcTemplate.query(
                    """
                    SELECT staged.employee_id, staged.source_version, staged.name, staged.email, staged.phone,
                           staged.manager_id, staged.office_code, staged.shift, staged.active,
                           staged.fingerprint
                    FROM snapshot_staging staged
                    LEFT JOIN employees employee
                      ON employee.tenant_id = staged.tenant_id
                     AND employee.employee_id = staged.employee_id
                    WHERE staged.run_id = ? AND staged.tenant_id = ? AND staged.valid
                      AND staged.employee_id > ?
                      AND (employee.employee_id IS NULL
                           OR staged.source_version > employee.hr_version
                           OR (staged.source_version = employee.hr_version
                               AND staged.fingerprint <> employee.hr_hash))
                    ORDER BY staged.employee_id LIMIT ?
                    """,
                    (result, rowNumber) -> new StagedEmployee(
                            result.getString("employee_id"),
                            result.getLong("source_version"),
                            new EmployeeState(result.getString("name"), result.getString("email"),
                                    result.getString("phone"), result.getString("manager_id"),
                                    result.getString("office_code"), result.getString("shift"),
                                    result.getBoolean("active")),
                            result.getString("fingerprint")),
                    runId, tenantId.value(), lastEmployeeId, batchSize);
            if (rows.isEmpty()) {
                break;
            }
            List<ValidatedHrEvent> events = rows.stream()
                    .map(row -> new ValidatedHrEvent(
                        new EmployeeId(row.employeeId()),
                        sourceSystem,
                        new SourceVersion(row.version()),
                        EventType.UPDATE,
                        row.state(),
                        row.fingerprint()))
                    .toList();
            List<com.moveinsync.roster.dto.EventProcessingResult> results =
                    snapshotApplyBatchService.apply(tenantId, events);
            for (int index = 0; index < rows.size(); index++) {
                StagedEmployee row = rows.get(index);
                var result = results.get(index);
                if ("REJECTED".equals(result.status()) || "VERSION_CONFLICT".equals(result.status())) {
                    jdbcTemplate.update(
                            """
                            INSERT INTO snapshot_errors (run_id, employee_id, error_code)
                            VALUES (?, ?, ?)
                            ON CONFLICT DO NOTHING
                            """,
                            runId, row.employeeId(), result.errorCode());
                }
                lastEmployeeId = row.employeeId();
            }
        }

        lastEmployeeId = "";
        while (true) {
            List<MissingEmployee> missingEmployees = jdbcTemplate.query(
                    """
                    SELECT employee.employee_id, employee.hr_version
                    FROM employees employee
                    WHERE employee.tenant_id = ? AND employee.hr_source_system = ? AND employee.active
                      AND employee.created_at <= ? AND employee.hr_updated_at <= ?
                      AND employee.employee_id > ?
                      AND NOT EXISTS (
                          SELECT 1 FROM snapshot_staging staged
                          WHERE staged.run_id = ? AND staged.tenant_id = employee.tenant_id
                            AND staged.employee_id = employee.employee_id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM snapshot_errors error_row
                          WHERE error_row.run_id = ? AND error_row.employee_id = employee.employee_id
                      )
                    ORDER BY employee.employee_id LIMIT ?
                    """,
                    (result, rowNumber) -> new MissingEmployee(
                            result.getString("employee_id"), result.getLong("hr_version")),
                    tenantId.value(), sourceSystem, cutoff, cutoff, lastEmployeeId, runId, runId, batchSize);
            if (missingEmployees.isEmpty()) {
                break;
            }
            for (MissingEmployee employee : missingEmployees) {
                employeeSyncService.deactivateMissing(
                        tenantId, sourceSystem, employee.employeeId(), employee.version(), cutoff, runId);
                lastEmployeeId = employee.employeeId();
            }
        }
        jdbcTemplate.update(
                "UPDATE snapshot_runs SET state = 'COMPLETED', updated_at = now() WHERE run_id = ?",
                runId);
    }

    private void decide(
            TenantId tenantId, UUID runId, String operatorId, String reason, String decision) {
        if (operatorId == null || operatorId.isBlank() || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ValidationException("INVALID_OPERATOR_DECISION", "reason");
        }
        decisionService.record(tenantId, runId, operatorId, reason, decision);
    }

    private void protectSourceConflicts(TenantId tenantId, UUID runId, String sourceSystem) {
        jdbcTemplate.update(
                """
                INSERT INTO snapshot_errors (run_id, employee_id, error_code)
                SELECT staged.run_id, staged.employee_id, 'SOURCE_SYSTEM_CONFLICT'
                FROM snapshot_staging staged
                JOIN employees employee
                  ON employee.tenant_id = staged.tenant_id
                 AND employee.employee_id = staged.employee_id
                WHERE staged.run_id = ? AND staged.tenant_id = ?
                  AND employee.hr_source_system <> ?
                  AND NOT EXISTS (
                      SELECT 1 FROM snapshot_errors error_row
                      WHERE error_row.run_id = staged.run_id
                        AND error_row.employee_id = staged.employee_id
                        AND error_row.error_code = 'SOURCE_SYSTEM_CONFLICT'
                  )
                """,
                runId, tenantId.value(), sourceSystem);
        jdbcTemplate.update(
                """
                UPDATE snapshot_staging staged SET valid = false
                FROM employees employee
                WHERE staged.run_id = ? AND staged.tenant_id = ?
                  AND employee.tenant_id = staged.tenant_id
                  AND employee.employee_id = staged.employee_id
                  AND employee.hr_source_system <> ?
                """,
                runId, tenantId.value(), sourceSystem);
    }

    private void ensureRunExists(TenantId tenantId, UUID runId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM snapshot_runs WHERE tenant_id = ? AND run_id = ?)",
                Boolean.class, tenantId.value(), runId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResourceNotFoundException("SNAPSHOT_NOT_FOUND");
        }
    }

    private void markFailed(UUID runId, UploadSummary summary, String errorCode) {
        jdbcTemplate.update(
                """
                UPDATE snapshot_runs SET uploaded_row_count = ?, uploaded_checksum = ?,
                    state = 'FAILED', updated_at = now() WHERE run_id = ?
                """,
                summary.rowCount(), summary.checksum(), runId);
        logger.warn("snapshot_upload_failed runId={} errorCode={}", runId, errorCode);
    }

    private String readState(UUID runId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM snapshot_runs WHERE run_id = ?", String.class, runId);
    }

    private String safeField(CSVRecord record, String field) {
        try {
            String value = record.isMapped(field) ? blankToNull(record.get(field)) : null;
            return value != null && value.length() > 100 ? null : value;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private Long nullableLong(java.sql.ResultSet result, String column) throws java.sql.SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private record StagedEmployee(String employeeId, long version, EmployeeState state, String fingerprint) {
    }

    private record SnapshotError(String employeeId, String errorCode) {
    }

    private record MissingEmployee(String employeeId, long version) {
    }

    private record UploadSummary(long rowCount, String checksum) {
    }

    private record RunDetails(String tenantId, String sourceSystem, OffsetDateTime cutoff) {
    }

    private record DiffCounts(long unchanged, long updated, long created, long stale) {
    }
}
