package com.moveinsync.roster.core;

public record EmployeeId(String value) {
    public EmployeeId {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException("Employee ID is invalid");
        }
    }
}
