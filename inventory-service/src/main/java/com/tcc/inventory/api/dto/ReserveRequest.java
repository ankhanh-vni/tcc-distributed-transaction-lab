package com.tcc.inventory.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;

import java.util.UUID;

public record ReserveRequest(
        @NotNull UUID txId,
        @NotBlank @Size(max = 64) String sku,
        @Min(1) int qty
) {
}
