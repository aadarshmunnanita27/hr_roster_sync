package com.moveinsync.roster.service;

import com.moveinsync.roster.core.EmployeeId;
import com.moveinsync.roster.core.EmployeeState;
import com.moveinsync.roster.core.EventType;
import com.moveinsync.roster.core.SourceVersion;

public record ValidatedHrEvent(
        EmployeeId employeeId,
        String sourceSystem,
        SourceVersion sourceVersion,
        EventType eventType,
        EmployeeState state,
        String fingerprint
) {
}
