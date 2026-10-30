package com.moveinsync.roster.dto;

import jakarta.validation.constraints.Size;

public record TransportPreferencesRequest(
        @Size(max = 200) String pickupPoint,
        boolean specialAssistance
) {
}
