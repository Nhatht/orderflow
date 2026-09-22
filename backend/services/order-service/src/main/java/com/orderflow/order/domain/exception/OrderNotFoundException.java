package com.orderflow.order.domain.exception;

import java.util.UUID;

/** Không tìm thấy đơn hàng với id đã cho. */
public class OrderNotFoundException extends RuntimeException {

    private final UUID orderId;

    public OrderNotFoundException(UUID orderId) {
        super("Order not found: " + orderId);
        this.orderId = orderId;
    }

    public UUID orderId() {
        return orderId;
    }
}
