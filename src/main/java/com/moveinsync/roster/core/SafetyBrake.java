package com.moveinsync.roster.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class SafetyBrake {
    public record Assessment(long activeEmployees, long snapshotEmployees,
                             long missingEmployees, BigDecimal missingPercentage,
                             BigDecimal thresholdPercent, boolean approvalRequired) {
        public ReconciliationDecision decision() {
            return approvalRequired
                    ? ReconciliationDecision.APPROVAL_REQUIRED
                    : ReconciliationDecision.APPLY;
        }
    }

    public Assessment assess(long activeEmployees, long snapshotEmployees, BigDecimal thresholdPercent) {
        if (activeEmployees < 0 || snapshotEmployees < 0
                || thresholdPercent == null || thresholdPercent.compareTo(BigDecimal.ZERO) < 0
                || thresholdPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Population counts and threshold are out of range");
        }

        long missing = Math.max(0, activeEmployees - snapshotEmployees);
        BigDecimal percentage = activeEmployees == 0
                ? BigDecimal.ZERO.setScale(3)
                : BigDecimal.valueOf(missing)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(activeEmployees), 3, RoundingMode.HALF_UP);

        return new Assessment(activeEmployees, snapshotEmployees, missing, percentage,
                thresholdPercent, percentage.compareTo(thresholdPercent) >= 0);
    }
}
