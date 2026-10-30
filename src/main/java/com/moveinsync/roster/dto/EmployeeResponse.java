package com.moveinsync.roster.dto;

import java.time.OffsetDateTime;

public record EmployeeResponse(
        String tenantId,
        String employeeId,
        String name,
        String email,
        String phone,
        String managerId,
        String officeCode,
        String shift,
        String pickupPoint,
        boolean specialAssistance,
        boolean active,
        long sourceVersion,
        OffsetDateTime updatedAt
) {
}
