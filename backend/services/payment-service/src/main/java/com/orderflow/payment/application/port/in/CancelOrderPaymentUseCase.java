package com.orderflow.payment.application.port.in;

import java.util.UUID;

/** Saga đã huỷ đơn → đảm bảo đơn đó không bao giờ bị thu tiền. */
public interface CancelOrderPaymentUseCase {

    void onOrderCancelled(UUID orderId, String reason);
}
