package com.moveinsync.roster.repository;

import com.moveinsync.roster.dto.OfficeRequest;
import com.moveinsync.roster.dto.OfficeResponse;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OfficeRepository {
    private static final RowMapper<OfficeResponse> OFFICE_MAPPER = (result, rowNumber) ->
            new OfficeResponse(
                    result.getString("office_code"),
                    result.getString("office_name"),
                    result.getBoolean("active"));

    private final JdbcTemplate jdbcTemplate;

    public OfficeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OfficeResponse> findAllByTenant(String tenantId) {
        return jdbcTemplate.query(
                """
                SELECT office_code, office_name, active
                FROM offices WHERE tenant_id = ? ORDER BY office_code
                """,
                OFFICE_MAPPER,
                tenantId);
    }

    public void save(String tenantId, OfficeRequest request) {
        jdbcTemplate.update(
                """
                INSERT INTO offices (tenant_id, office_code, office_name, active)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, office_code)
                DO UPDATE SET office_name = EXCLUDED.office_name, active = EXCLUDED.active
                """,
                tenantId, request.officeCode(), request.officeName(),
                request.active() == null || request.active());
    }

    public boolean deactivate(String tenantId, String officeCode) {
        return jdbcTemplate.update(
                "UPDATE offices SET active = false WHERE tenant_id = ? AND office_code = ?",
                tenantId, officeCode) > 0;
    }
}
