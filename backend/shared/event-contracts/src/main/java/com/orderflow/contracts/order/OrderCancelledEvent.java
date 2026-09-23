package com.orderflow.contracts.order;

import java.util.UUID;

/**
 * Đơn bị huỷ. Với inventory, đây là lệnh ĐỀN BÙ: nhả mọi hàng đang giữ cho đơn.
 *
 * <p>Inventory nhận event này cả khi nó CHƯA giữ gì (đơn huỷ vì hết hàng).
 * Không sao — thao tác nhả hàng idempotent, không có gì để nhả thì không làm gì.
 * Phát một event duy nhất cho mọi kiểu huỷ đơn giản hơn nhiều so với bắt
 * order-service nhớ "bên nào đã làm gì để đền bù đúng bên đó".
 */
public record OrderCancelledEvent(UUID orderId, Reason reason) {

    public static final String TYPE = "OrderCancelled";

    public enum Reason {
        /** Inventory không giữ được hàng — chưa có gì để đền bù. */
        STOCK_UNAVAILABLE,
        /** Thanh toán bị từ chối — phải nhả hàng đã giữ. */
        PAYMENT_DECLINED
    }
}
