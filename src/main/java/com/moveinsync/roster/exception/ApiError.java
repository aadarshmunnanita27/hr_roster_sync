package com.moveinsync.roster.exception;

public record ApiError(String errorCode, String message, String correlationId, String field) {
}
