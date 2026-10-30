package com.moveinsync.roster.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SnapshotRunResponse(
        UUID runId,
        String state,
        long uploadedRowCount,
        Long activeEmployeeCount,
        Long snapshotEmployeeCount,
        Long unchangedEmployeeCount,
        Long updatedEmployeeCount,
        Long newEmployeeCount,
        Long staleEmployeeCount,
        Long missingEmployeeCount,
        BigDecimal missingPercentage,
        BigDecimal safetyThresholdPercent,
        OffsetDateTime createdAt
) {
}
