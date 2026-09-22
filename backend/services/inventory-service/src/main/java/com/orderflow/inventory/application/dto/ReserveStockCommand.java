package com.orderflow.inventory.application.dto;

import java.util.UUID;

/** Lệnh giữ hàng cho một đơn. */
public record ReserveStockCommand(UUID orderId, UUID productId, int quantity) {}
