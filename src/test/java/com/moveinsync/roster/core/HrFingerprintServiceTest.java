package com.moveinsync.roster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class HrFingerprintServiceTest {
    private final HrFingerprintService fingerprintService = new HrFingerprintService();

    @Test
    void createsStableFingerprintsFromCanonicalHrOwnedState() {
        EmployeeState employee = new EmployeeState(
                "Example Worker", "worker@example.invalid", "+10000000000",
                "MGR-1", "BLR01", "DAY", true);

        assertEquals(fingerprintService.fingerprint(employee), fingerprintService.fingerprint(employee));
    }

    @Test
    void changesFingerprintWhenAnHrOwnedFieldChanges() {
        EmployeeState before = new EmployeeState(
                "Example Worker", "worker@example.invalid", "+10000000000",
                "MGR-1", "BLR01", "DAY", true);
        EmployeeState after = new EmployeeState(
                "Example Worker", "worker@example.invalid", "+10000000000",
                "MGR-1", "BLR02", "DAY", true);

        assertNotEquals(fingerprintService.fingerprint(before), fingerprintService.fingerprint(after));
    }
}
