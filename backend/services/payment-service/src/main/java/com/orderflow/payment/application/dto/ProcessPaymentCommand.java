package com.orderflow.payment.application.dto;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Lệnh thu tiền cho một đơn.
 *
 * <p>Không có {@code idempotencyKey} kiểu eventId như bên inventory — có chủ
 * đích. Ở đây khoá idempotency tự nhiên là {@code orderId}: "thu tiền cho
 * đơn X" chỉ được xảy ra một lần, bất kể lệnh đến bao nhiêu lần và mang
 * eventId nào. Nếu saga sau này phát lại lệnh (ví dụ job đối soát), eventId
 * sẽ khác — khoá theo eventId sẽ để lọt, khoá theo orderId thì không.
 */
public record ProcessPaymentCommand(
        String correlationId,
        UUID orderId,
        UUID customerId,
        BigDecimal amount,
        String currency
) {
    public ProcessPaymentCommand {
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }
}
