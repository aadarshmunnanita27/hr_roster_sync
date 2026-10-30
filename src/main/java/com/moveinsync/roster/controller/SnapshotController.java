package com.moveinsync.roster.controller;

import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.dto.SnapshotDecisionRequest;
import com.moveinsync.roster.dto.SnapshotErrorResponse;
import com.moveinsync.roster.dto.SnapshotRunResponse;
import com.moveinsync.roster.exception.TenantAccessException;
import com.moveinsync.roster.security.CurrentActor;
import com.moveinsync.roster.service.SnapshotService;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/v1/hr/snapshots")
public class SnapshotController {
    private final SnapshotService snapshotService;

    public SnapshotController(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasRole('HR_SOURCE')")
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotRunResponse upload(
            @RequestParam String sourceSystem,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime snapshotCutoff,
            @RequestParam(required = false) Long expectedRowCount,
            @RequestParam(required = false) String expectedChecksum,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        CurrentActor actor = CurrentActor.from(authentication);
        if (actor.tenantId() == null || actor.tenantId().isBlank() || actor.tenantId().length() > 80) {
            throw new TenantAccessException();
        }
        if (actor.sourceSystem() == null || !actor.sourceSystem().equals(sourceSystem)) {
            throw new TenantAccessException();
        }
        return snapshotService.upload(new TenantId(actor.tenantId()), sourceSystem, snapshotCutoff,
                expectedRowCount, expectedChecksum, file);
    }

    @GetMapping("/{runId}")
    @PreAuthorize("hasRole('OPERATOR')")
    public SnapshotRunResponse getRun(@PathVariable UUID runId, Authentication authentication) {
        return snapshotService.getRun(tenant(authentication), runId);
    }

    @GetMapping("/{runId}/errors")
    @PreAuthorize("hasRole('OPERATOR')")
    public List<SnapshotErrorResponse> getErrors(@PathVariable UUID runId, Authentication authentication) {
        return snapshotService.getErrors(tenant(authentication), runId);
    }

    @PostMapping("/{runId}/approve")
    @PreAuthorize("hasRole('OPERATOR')")
    public SnapshotRunResponse approve(
            @PathVariable UUID runId,
            @Valid @RequestBody SnapshotDecisionRequest request,
            Authentication authentication) {
        CurrentActor actor = CurrentActor.from(authentication);
        return snapshotService.approve(tenant(authentication), runId, actor.subject(), request.reason());
    }

    @PostMapping("/{runId}/reject")
    @PreAuthorize("hasRole('OPERATOR')")
    public SnapshotRunResponse reject(
            @PathVariable UUID runId,
            @Valid @RequestBody SnapshotDecisionRequest request,
            Authentication authentication) {
        CurrentActor actor = CurrentActor.from(authentication);
        return snapshotService.reject(tenant(authentication), runId, actor.subject(), request.reason());
    }

    private TenantId tenant(Authentication authentication) {
        String tenantId = CurrentActor.from(authentication).tenantId();
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 80) {
            throw new TenantAccessException();
        }
        return new TenantId(tenantId);
    }
}
