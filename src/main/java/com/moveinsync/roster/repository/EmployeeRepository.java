package com.moveinsync.roster.repository;

import com.moveinsync.roster.dto.EmployeeResponse;
import com.moveinsync.roster.exception.ResourceNotFoundException;
import com.moveinsync.roster.dto.TransportPreferencesRequest;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeRepository {
    private static final RowMapper<EmployeeResponse> EMPLOYEE_MAPPER = (result, rowNumber) ->
            new EmployeeResponse(
                    result.getString("tenant_id"),
                    result.getString("employee_id"),
                    result.getString("name"),
                    result.getString("email"),
                    result.getString("phone"),
                    result.getString("manager_id"),
                    result.getString("office_code"),
                    result.getString("shift"),
                    result.getString("pickup_point"),
                    result.getBoolean("special_assistance"),
                    result.getBoolean("active"),
                    result.getLong("hr_version"),
                    result.getObject("updated_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public EmployeeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<EmployeeResponse> findByTenantAndId(String tenantId, String employeeId) {
        return jdbcTemplate.query(
                        """
                        SELECT tenant_id, employee_id, name, email, phone, manager_id, office_code, shift,
                               pickup_point, special_assistance, active, hr_version, updated_at
                        FROM employees WHERE tenant_id = ? AND employee_id = ?
                        """,
                        EMPLOYEE_MAPPER,
                        tenantId, employeeId)
                .stream()
                .findFirst();
    }

    public boolean updateTransportPreferences(
            String tenantId, String employeeId, TransportPreferencesRequest request) {
        return jdbcTemplate.update(
                """
                UPDATE employees SET pickup_point = ?, special_assistance = ?, updated_at = now()
                WHERE tenant_id = ? AND employee_id = ?
                """,
                request.pickupPoint(), request.specialAssistance(), tenantId, employeeId) > 0;
    }
}
