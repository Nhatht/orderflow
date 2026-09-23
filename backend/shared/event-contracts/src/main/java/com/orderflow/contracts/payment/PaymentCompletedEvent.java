package com.orderflow.contracts.payment;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.UUID;

/** Đã thu tiền thành công cho đơn. */
public record PaymentCompletedEvent(
        UUID orderId,
        UUID paymentId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency
) {
    public static final String TYPE = "PaymentCompleted";
}
