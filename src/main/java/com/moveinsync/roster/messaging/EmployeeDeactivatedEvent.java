package com.moveinsync.roster.messaging;

import java.util.UUID;

public final class EmployeeDeactivatedEvent extends AbstractDomainEvent {
    public EmployeeDeactivatedEvent(UUID eventId, String tenantId, String employeeId, long sourceVersion) {
        super(eventId, tenantId, employeeId, "EMPLOYEE_DEACTIVATED", sourceVersion);
    }
}
