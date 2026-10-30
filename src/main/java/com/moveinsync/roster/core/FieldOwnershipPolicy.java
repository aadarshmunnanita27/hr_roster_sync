package com.moveinsync.roster.core;

import java.util.Set;

public class FieldOwnershipPolicy {
    private static final Set<String> HR_FIELDS =
            Set.of("name", "email", "phone", "managerId", "officeCode", "shift", "active");
    private static final Set<String> TRANSPORT_FIELDS =
            Set.of("pickupPoint", "specialAssistance");

    public String invalidField(String field) {
        if (TRANSPORT_FIELDS.contains(field)) {
            return "TRANSPORT_FIELD_NOT_ALLOWED";
        }
        return HR_FIELDS.contains(field) ? null : "UNKNOWN_FIELD";
    }
}
