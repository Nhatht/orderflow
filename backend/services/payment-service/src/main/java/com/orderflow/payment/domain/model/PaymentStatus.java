package com.orderflow.payment.domain.model;

/**
 * Trạng thái thanh toán.
 *
 * <pre>
 *   PENDING ──▶ COMPLETED
 *      └──────▶ FAILED
 * </pre>
 *
 * PENDING nghĩa là "đã quyết định thu, CHƯA BIẾT kết quả". Payment kẹt ở
 * PENDING không phải lỗi dữ liệu — nó là dấu vết của một lần gọi cổng bị gián
 * đoạn, và lần xử lý sau sẽ gọi lại với cùng idempotency key để biết kết quả.
 */
public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
