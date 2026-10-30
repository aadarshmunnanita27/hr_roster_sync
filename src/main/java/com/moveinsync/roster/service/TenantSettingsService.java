package com.moveinsync.roster.service;

import com.github.benmanes.caffeine.cache.Cache;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TenantSettingsService {
    private final JdbcTemplate jdbcTemplate;
    private final Cache<String, BigDecimal> settingsCache;
    private final BigDecimal defaultThreshold;

    public TenantSettingsService(
            JdbcTemplate jdbcTemplate,
            Cache<String, BigDecimal> settingsCache,
            @Value("${roster.reconciliation.safety-threshold-percent:30}") BigDecimal defaultThreshold) {
        this.jdbcTemplate = jdbcTemplate;
        this.settingsCache = settingsCache;
        this.defaultThreshold = defaultThreshold;
    }

    public BigDecimal safetyThreshold(String tenantId) {
        return settingsCache.get(tenantId, key -> jdbcTemplate.query(
                        "SELECT safety_threshold_percent FROM tenant_settings WHERE tenant_id = ?",
                        (result, rowNumber) -> result.getBigDecimal(1),
                        tenantId)
                .stream()
                .findFirst()
                .orElse(defaultThreshold));
    }

    public void updateSafetyThreshold(String tenantId, BigDecimal threshold) {
        jdbcTemplate.update(
                """
                INSERT INTO tenant_settings (tenant_id, safety_threshold_percent)
                VALUES (?, ?)
                ON CONFLICT (tenant_id)
                DO UPDATE SET safety_threshold_percent = EXCLUDED.safety_threshold_percent,
                    updated_at = now()
                """,
                tenantId, threshold);
        settingsCache.put(tenantId, threshold);
    }
}
