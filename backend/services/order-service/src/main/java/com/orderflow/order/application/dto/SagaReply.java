package com.orderflow.order.application.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Một phản hồi gửi về saga — từ inventory hoặc payment.
 *
 * @param eventId       khoá idempotency (sổ {@code processed_events})
 * @param correlationId mang tiếp sang lệnh kế tiếp mà saga phát ra
 * @param detail        lý do thất bại, nếu có
 */
public record SagaReply(UUID eventId, String correlationId, UUID orderId, String detail) {

    public SagaReply {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
    }

    public static SagaReply of(UUID eventId, String correlationId, UUID orderId) {
        return new SagaReply(eventId, correlationId, orderId, null);
    }
}
