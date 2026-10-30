package com.moveinsync.roster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class FieldOwnershipPolicyTest {
    private final FieldOwnershipPolicy policy = new FieldOwnershipPolicy();

    @Test
    void acceptsHrOwnedFields() {
        assertNull(policy.invalidField("officeCode"));
        assertNull(policy.invalidField("shift"));
    }

    @Test
    void rejectsTransportOwnedAndUnknownFields() {
        assertEquals("TRANSPORT_FIELD_NOT_ALLOWED", policy.invalidField("pickupPoint"));
        assertEquals("UNKNOWN_FIELD", policy.invalidField("homeAddress"));
    }
}
