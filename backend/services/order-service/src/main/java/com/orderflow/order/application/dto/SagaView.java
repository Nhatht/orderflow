package com.orderflow.order.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Hình chiếu saga của một đơn: trạng thái hiện tại + toàn bộ các bước đã qua.
 * Đây là dữ liệu cho trang saga timeline ở frontend (tuần 11).
 */
public record SagaView(
        UUID orderId,
        String status,
        String currentStep,
        String failureReason,
        Instant startedAt,
        Instant updatedAt,
        List<Step> steps
) {
    public record Step(String step, String outcome, String detail, Instant occurredAt) {}
}
