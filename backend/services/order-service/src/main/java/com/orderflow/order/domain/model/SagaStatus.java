package com.orderflow.order.domain.model;

/**
 * Trạng thái saga đặt hàng.
 *
 * <pre>
 *   STARTED ──stock.reserved──▶ AWAITING_PAYMENT ──payment.completed──▶ COMPLETED
 *      │                              │
 *      │ stock.reservation-failed     │ payment.failed
 *      ▼                              ▼
 *   FAILED                      COMPENSATING ──stock.released──▶ COMPENSATED
 *   (chưa giữ gì,               (đã giữ hàng,
 *    không cần đền bù)           đang chờ inventory nhả)
 * </pre>
 *
 * <p>Tách FAILED và COMPENSATED có chủ đích: cả hai đều là "đơn huỷ", nhưng
 * COMPENSATED nói thêm "đã có tác dụng phụ và nó ĐÃ được hoàn tác". Khi gỡ lỗi
 * một đơn huỷ, câu hỏi đầu tiên luôn là "hàng đã được trả về kho chưa?".
 */
public enum SagaStatus {
    STARTED,
    AWAITING_PAYMENT,
    COMPLETED,
    COMPENSATING,
    COMPENSATED,
    FAILED;

    public boolean isFinal() {
        return this == COMPLETED || this == COMPENSATED || this == FAILED;
    }
}
