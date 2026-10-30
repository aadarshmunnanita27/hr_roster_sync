package com.moveinsync.roster.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ReconciliationSettingsRequest(
        @NotNull @DecimalMin("0.000") @DecimalMax("100.000") BigDecimal safetyThresholdPercent
) {
}
