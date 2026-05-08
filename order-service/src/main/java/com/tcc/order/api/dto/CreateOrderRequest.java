package com.tcc.order.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull UUID txId,
        @NotBlank String customerId,
        @NotBlank String sku,
        @Min(1) int qty,
        @NotNull @DecimalMin("0.01") BigDecimal amount
) {
}
