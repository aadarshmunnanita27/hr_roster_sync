package com.moveinsync.roster.core;

public record TenantId(String value) {
    public TenantId {
        if (value == null || value.isBlank() || value.length() > 80) {
            throw new IllegalArgumentException("Tenant ID is invalid");
        }
    }
}
