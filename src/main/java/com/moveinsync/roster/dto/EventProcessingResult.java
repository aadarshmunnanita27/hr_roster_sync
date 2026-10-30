package com.moveinsync.roster.dto;

public record EventProcessingResult(
        String employeeId,
        String status,
        String errorCode,
        String field,
        String message
) {
    public static EventProcessingResult applied(String employeeId, String status) {
        return new EventProcessingResult(employeeId, status, null, null, null);
    }

    public static EventProcessingResult rejected(String employeeId, String errorCode, String field) {
        return new EventProcessingResult(employeeId, "REJECTED", errorCode, field,
                "The record could not be accepted.");
    }
}
