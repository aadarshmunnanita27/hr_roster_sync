package com.moveinsync.roster.messaging;

import java.util.UUID;

public final class EmployeeCreatedEvent extends AbstractDomainEvent {
    public EmployeeCreatedEvent(UUID eventId, String tenantId, String employeeId, long sourceVersion) {
        super(eventId, tenantId, employeeId, "EMPLOYEE_CREATED", sourceVersion);
    }
}
