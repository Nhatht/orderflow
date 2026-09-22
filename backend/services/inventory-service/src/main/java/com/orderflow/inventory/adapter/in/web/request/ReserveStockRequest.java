package com.orderflow.inventory.adapter.in.web.request;

import com.orderflow.inventory.application.dto.ReserveStockCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

@Schema(description = "Request to reserve stock for an order")
public record ReserveStockRequest(

        @NotNull(message = "orderId is required")
        UUID orderId,

        @NotNull(message = "productId is required")
        @Schema(example = "33333333-3333-3333-3333-333333333333")
        UUID productId,

        @Positive(message = "quantity must be positive")
        @Schema(example = "1")
        int quantity
) {
    public ReserveStockCommand toCommand() {
        return new ReserveStockCommand(orderId, productId, quantity);
    }
}
