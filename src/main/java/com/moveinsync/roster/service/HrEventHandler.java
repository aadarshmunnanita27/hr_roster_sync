package com.moveinsync.roster.service;

import com.moveinsync.roster.core.EventType;
import com.moveinsync.roster.core.TenantId;
import com.moveinsync.roster.dto.EventProcessingResult;

public interface HrEventHandler {
    EventType eventType();

    EventProcessingResult handle(TenantId tenantId, ValidatedHrEvent event);
}
