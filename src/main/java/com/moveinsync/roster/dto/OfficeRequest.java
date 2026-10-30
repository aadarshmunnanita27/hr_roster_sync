package com.moveinsync.roster.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OfficeRequest(
        @NotBlank @Size(max = 40) String officeCode,
        @NotBlank @Size(max = 120) String officeName,
        Boolean active
) {
}
