package com.moveinsync.roster.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SnapshotDecisionRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
