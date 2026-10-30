package com.moveinsync.roster.exception;

public class RosterException extends RuntimeException {
    private final String errorCode;
    private final String field;
    private final int httpStatus;

    public RosterException(String errorCode, String field, int httpStatus) {
        super(errorCode);
        this.errorCode = errorCode;
        this.field = field;
        this.httpStatus = httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public String field() {
        return field;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
