package com.moveinsync.roster.controller;

import com.moveinsync.roster.dto.ReconciliationSettingsRequest;
import com.moveinsync.roster.exception.TenantAccessException;
import com.moveinsync.roster.security.CurrentActor;
import com.moveinsync.roster.service.TenantSettingsService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reconciliation-settings")
@PreAuthorize("hasRole('TRANSPORT_ADMIN')")
public class TenantSettingsController {
    private final TenantSettingsService settingsService;

    public TenantSettingsController(TenantSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public Map<String, BigDecimal> get(Authentication authentication) {
        return Map.of("safetyThresholdPercent",
                settingsService.safetyThreshold(requireTenant(authentication)));
    }

    @PutMapping
    public Map<String, BigDecimal> update(
            @Valid @RequestBody ReconciliationSettingsRequest request,
            Authentication authentication) {
        String tenantId = requireTenant(authentication);
        settingsService.updateSafetyThreshold(tenantId, request.safetyThresholdPercent());
        return Map.of("safetyThresholdPercent", request.safetyThresholdPercent());
    }

    private String requireTenant(Authentication authentication) {
        String tenantId = CurrentActor.from(authentication).tenantId();
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 80) {
            throw new TenantAccessException();
        }
        return tenantId;
    }
}
