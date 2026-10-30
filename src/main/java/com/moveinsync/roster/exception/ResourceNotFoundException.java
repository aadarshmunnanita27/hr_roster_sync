package com.moveinsync.roster.exception;

public class ResourceNotFoundException extends RosterException {
    public ResourceNotFoundException(String errorCode) {
        super(errorCode, null, 404);
    }
}
