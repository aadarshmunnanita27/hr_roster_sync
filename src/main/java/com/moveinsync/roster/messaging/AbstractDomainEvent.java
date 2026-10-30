package com.moveinsync.roster.messaging;

import java.util.UUID;

public abstract class AbstractDomainEvent {
    private final UUID eventId;
    private final String tenantId;
    private final String employeeId;
    private final String eventType;
    private final long sourceVersion;

    protected AbstractDomainEvent(
            UUID eventId, String tenantId, String employeeId, String eventType, long sourceVersion) {
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.employeeId = employeeId;
        this.eventType = eventType;
        this.sourceVersion = sourceVersion;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getEventType() {
        return eventType;
    }

    public long getSourceVersion() {
        return sourceVersion;
    }
}
