package com.moveinsync.roster.service;

import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.exception.ResourceNotFoundException;
import com.moveinsync.roster.exception.ValidationException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SnapshotDecisionService {
    private final JdbcTemplate jdbcTemplate;

    public SnapshotDecisionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void record(TenantId tenantId, UUID runId, String operatorId, String reason, String decision) {
        int updated = jdbcTemplate.update(
                """
                UPDATE snapshot_runs SET state = ?, updated_at = now()
                WHERE tenant_id = ? AND run_id = ? AND state = 'APPROVAL_REQUIRED'
                """,
                decision.equals("APPROVE") ? "APPROVED" : "REJECTED", tenantId.value(), runId);
        if (updated == 0) {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM snapshot_runs WHERE tenant_id = ? AND run_id = ?)",
                    Boolean.class, tenantId.value(), runId);
            if (!Boolean.TRUE.equals(exists)) {
                throw new ResourceNotFoundException("SNAPSHOT_NOT_FOUND");
            }
            throw new ValidationException("SNAPSHOT_NOT_AWAITING_APPROVAL", "runId");
        }
        jdbcTemplate.update(
                """
                INSERT INTO audit_logs (tenant_id, run_id, operator_id, decision, reason)
                VALUES (?, ?, ?, ?, ?)
                """,
                tenantId.value(), runId, operatorId, decision, reason.trim());
    }
}
