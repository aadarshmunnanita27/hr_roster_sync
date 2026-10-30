package com.moveinsync.roster.dto;

import java.util.Map;

public record HrEventRequest(
        String tenantId,
        String employeeId,
        String sourceSystem,
        Long sourceVersion,
        String eventType,
        Map<String, Object> data
) {
}
