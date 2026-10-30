package com.moveinsync.roster.service;

import com.moveinsync.roster.core.EventType;
import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.dto.EventProcessingResult;
import org.springframework.stereotype.Component;

@Component
public class UpdateEventHandler implements HrEventHandler {
    private final EmployeeSyncService employeeSyncService;

    public UpdateEventHandler(EmployeeSyncService employeeSyncService) {
        this.employeeSyncService = employeeSyncService;
    }

    @Override
    public EventType eventType() {
        return EventType.UPDATE;
    }

    @Override
    public EventProcessingResult handle(TenantId tenantId, ValidatedHrEvent event) {
        return employeeSyncService.process(tenantId, event);
    }
}
