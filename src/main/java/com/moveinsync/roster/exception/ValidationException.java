package com.moveinsync.roster.exception;

public class ValidationException extends RosterException {
    public ValidationException(String errorCode, String field) {
        super(errorCode, field, 400);
    }
}
