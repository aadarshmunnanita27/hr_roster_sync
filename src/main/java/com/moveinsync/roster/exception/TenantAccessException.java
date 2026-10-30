package com.moveinsync.roster.exception;

public class TenantAccessException extends RosterException {
    public TenantAccessException() {
        super("TENANT_ACCESS_DENIED", null, 403);
    }
}
