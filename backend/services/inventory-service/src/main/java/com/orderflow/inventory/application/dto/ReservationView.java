package com.orderflow.inventory.application.dto;

import com.orderflow.inventory.domain.model.StockReservation;

import java.time.Instant;
import java.util.UUID;

public record ReservationView(
        UUID id,
        UUID orderId,
        UUID productId,
        int quantity,
        String status,
        Instant expiresAt,
        Instant createdAt
) {
    public static ReservationView from(StockReservation r) {
        return new ReservationView(r.id(), r.orderId(), r.productId(), r.quantity(),
                r.status().name(), r.expiresAt(), r.createdAt());
    }
}
