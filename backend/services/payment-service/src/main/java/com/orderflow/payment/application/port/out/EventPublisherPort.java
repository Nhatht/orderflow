package com.orderflow.payment.application.port.out;

import com.orderflow.payment.domain.model.Payment;

/**
 * CỔNG RA để báo kết quả thanh toán. PHẢI gọi trong transaction đang ghi
 * kết quả — cài đặt outbox ép bằng {@code Propagation.MANDATORY}.
 */
public interface EventPublisherPort {

    void publishPaymentCompleted(Payment payment, String correlationId);

    void publishPaymentFailed(Payment payment, String correlationId);
}
