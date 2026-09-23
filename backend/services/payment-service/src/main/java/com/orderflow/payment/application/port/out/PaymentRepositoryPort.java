package com.orderflow.payment.application.port.out;

import com.orderflow.payment.domain.model.Payment;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepositoryPort {

    Payment save(Payment payment);

    /** Một đơn có tối đa một payment — UNIQUE(order_id) dưới database. */
    Optional<Payment> findByOrderId(UUID orderId);
}
