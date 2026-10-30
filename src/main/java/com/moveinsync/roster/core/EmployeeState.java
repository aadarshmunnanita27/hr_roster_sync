package com.moveinsync.roster.core;

public record EmployeeState(
        String name,
        String email,
        String phone,
        String managerId,
        String officeCode,
        String shift,
        boolean active
) {
}
