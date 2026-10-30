package com.moveinsync.roster.messaging;

import java.util.UUID;

public final class EmployeeUpdatedEvent extends AbstractDomainEvent {
    public EmployeeUpdatedEvent(UUID eventId, String tenantId, String employeeId, long sourceVersion) {
        super(eventId, tenantId, employeeId, "EMPLOYEE_UPDATED", sourceVersion);
    }
}
