package com.orderflow.contracts.payment;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lệnh thu tiền — saga phát ra SAU KHI đã giữ được hàng.
 *
 * <p>Tên là "Requested" (một yêu cầu), không phải "Payment" hay "Charge":
 * payment-service có quyền từ chối. Event đặt tên theo điều ĐÃ xảy ra ở
 * bên phát — ở đây là "order-service đã yêu cầu thu tiền".
 *
 * <p>Thứ tự saga (giữ hàng TRƯỚC, thu tiền SAU) là countermeasure "pessimistic
 * view": bước dễ hỏng mà đền bù rẻ (giữ hàng — nhả là xong) đặt trước; bước
 * đền bù đắt (thu tiền — phải hoàn tiền, khách chờ 3–5 ngày) đặt sau. Xem
 * {@code docs/PATTERNS.md} mục 0.
 */
public record PaymentRequestedEvent(
        UUID orderId,
        UUID customerId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency
) {
    public static final String TYPE = "PaymentRequested";
}
