package com.moveinsync.roster.controller;

import com.moveinsync.roster.dto.EmployeeResponse;
import com.moveinsync.roster.dto.TransportPreferencesRequest;
import com.moveinsync.roster.exception.TenantAccessException;
import com.moveinsync.roster.security.CurrentActor;
import com.moveinsync.roster.service.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {
    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @GetMapping("/{employeeId}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'TRANSPORT_ADMIN', 'OPERATOR')")
    public EmployeeResponse getEmployee(@PathVariable String employeeId, Authentication authentication) {
        CurrentActor actor = CurrentActor.from(authentication);
        requireTenant(actor);
        boolean employeeRole = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_EMPLOYEE"));
        if (employeeRole && !employeeId.equals(actor.employeeId())) {
            throw new TenantAccessException();
        }

        return employeeService.getEmployee(actor.tenantId(), employeeId);
    }

    @PutMapping("/{employeeId}/transport-preferences")
    @PreAuthorize("hasRole('TRANSPORT_ADMIN')")
    public void updateTransportPreferences(
            @PathVariable String employeeId,
            @Valid @RequestBody TransportPreferencesRequest request,
            Authentication authentication) {
        CurrentActor actor = CurrentActor.from(authentication);
        requireTenant(actor);
        employeeService.updateTransportPreferences(actor.tenantId(), employeeId, request);
    }

    private void requireTenant(CurrentActor actor) {
        if (actor.tenantId() == null || actor.tenantId().isBlank() || actor.tenantId().length() > 80) {
            throw new TenantAccessException();
        }
    }
}
