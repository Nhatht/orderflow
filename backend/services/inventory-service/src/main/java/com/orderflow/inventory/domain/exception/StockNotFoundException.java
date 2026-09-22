package com.orderflow.inventory.domain.exception;

import java.util.UUID;

public class StockNotFoundException extends RuntimeException {

    private final UUID productId;

    public StockNotFoundException(UUID productId) {
        super("Stock not found for product: " + productId);
        this.productId = productId;
    }

    public UUID productId() { return productId; }
}
