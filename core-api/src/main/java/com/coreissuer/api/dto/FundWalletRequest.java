package com.coreissuer.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record FundWalletRequest(
        @NotNull @DecimalMin("1.00") @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @Pattern(regexp = "INR|USD") String currency) {
}
