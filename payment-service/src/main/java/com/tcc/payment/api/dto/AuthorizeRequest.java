package com.tcc.payment.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AuthorizeRequest(
        @NotNull UUID txId,
        @NotBlank String customerId,
        @NotNull @DecimalMin("0.01") BigDecimal amount
) {
}
