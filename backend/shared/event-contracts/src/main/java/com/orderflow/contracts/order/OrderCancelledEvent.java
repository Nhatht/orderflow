package com.orderflow.contracts.order;

import java.util.UUID;

/**
 * Đơn bị huỷ. Với inventory, đây là lệnh ĐỀN BÙ: nhả mọi hàng đang giữ cho đơn.
 *
 * <p>Inventory nhận event này cả khi nó CHƯA giữ gì (đơn huỷ vì hết hàng), hoặc
 * khi phiếu đã tự hết hạn. Không sao — thao tác nhả hàng idempotent, không có
 * gì để nhả thì không làm gì. Phát một event duy nhất cho mọi kiểu huỷ đơn giản
 * hơn nhiều so với bắt order-service nhớ "bên nào đã làm gì để đền bù đúng bên đó".
 *
 * @param customerId thêm ở tuần 7 cho notification-service — xem
 *                   {@link OrderConfirmedEvent}.
 */
public record OrderCancelledEvent(UUID orderId, UUID customerId, Reason reason) {

    public static final String TYPE = "OrderCancelled";

    public enum Reason {
        /** Inventory không giữ được hàng — chưa có gì để đền bù. */
        STOCK_UNAVAILABLE,
        /** Thanh toán bị từ chối — phải nhả hàng đã giữ. */
        PAYMENT_DECLINED,
        /** Phiếu giữ hàng hết hạn trước khi thanh toán xong — hàng đã tự về kho. */
        RESERVATION_EXPIRED,
        /**
         * Saga chờ tiền quá hạn (saga timeout) — phải nhả hàng đã giữ. Khác
         * RESERVATION_EXPIRED ở chỗ hàng VẪN đang được giữ: saga ra quyết định
         * trước TTL của phiếu. Xem {@code docs/SAGA-TIMEOUT.md}.
         */
        PAYMENT_TIMEOUT
    }
}
