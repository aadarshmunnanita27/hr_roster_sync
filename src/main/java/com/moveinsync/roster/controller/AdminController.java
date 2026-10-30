package com.moveinsync.roster.controller;

import com.moveinsync.roster.dto.OfficeRequest;
import com.moveinsync.roster.dto.OfficeResponse;
import com.moveinsync.roster.exception.TenantAccessException;
import com.moveinsync.roster.security.CurrentActor;
import com.moveinsync.roster.service.OfficeService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/offices")
@PreAuthorize("hasRole('TRANSPORT_ADMIN')")
public class AdminController {
    private final OfficeService officeService;

    public AdminController(OfficeService officeService) {
        this.officeService = officeService;
    }

    @GetMapping
    public List<OfficeResponse> list(Authentication authentication) {
        String tenantId = requireTenant(authentication);
        return officeService.list(tenantId);
    }

    @PutMapping
    public void save(@Valid @RequestBody OfficeRequest request, Authentication authentication) {
        String tenantId = requireTenant(authentication);
        officeService.save(tenantId, request);
    }

    @DeleteMapping("/{officeCode}")
    public void deactivate(@PathVariable String officeCode, Authentication authentication) {
        String tenantId = requireTenant(authentication);
        officeService.deactivate(tenantId, officeCode);
    }

    private String requireTenant(Authentication authentication) {
        String tenantId = CurrentActor.from(authentication).tenantId();
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 80) {
            throw new TenantAccessException();
        }
        return tenantId;
    }
}
