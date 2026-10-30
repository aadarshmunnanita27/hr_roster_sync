package com.moveinsync.roster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SafetyBrakeTest {
    private final SafetyBrake safetyBrake = new SafetyBrake();

    @Test
    void pausesA40PercentDropAtTheDefaultThreshold() {
        SafetyBrake.Assessment assessment =
                safetyBrake.assess(200_000, 120_000, BigDecimal.valueOf(30));

        assertEquals(80_000, assessment.missingEmployees());
        assertEquals(new BigDecimal("40.000"), assessment.missingPercentage());
        assertEquals(ReconciliationDecision.APPROVAL_REQUIRED, assessment.decision());
    }

    @Test
    void permitsExactlyTheConfiguredThreshold() {
        SafetyBrake.Assessment assessment =
                safetyBrake.assess(100, 70, BigDecimal.valueOf(30));

        assertTrue(assessment.approvalRequired());
        assertEquals(new BigDecimal("30.000"), assessment.missingPercentage());
    }

    @Test
    void handlesAnEmptyTenantWithoutDivisionByZero() {
        SafetyBrake.Assessment assessment =
                safetyBrake.assess(0, 0, BigDecimal.valueOf(30));

        assertEquals(BigDecimal.ZERO.setScale(3), assessment.missingPercentage());
        assertFalse(assessment.approvalRequired());
    }

    @Test
    void thresholdZeroRequiresApprovalEvenWhenNoEmployeesAreMissing() {
        assertTrue(safetyBrake.assess(0, 0, BigDecimal.ZERO).approvalRequired());
    }

    @Test
    void rejectsThresholdsOutsideTheirSupportedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> safetyBrake.assess(100, 90, BigDecimal.valueOf(101)));
    }
}
