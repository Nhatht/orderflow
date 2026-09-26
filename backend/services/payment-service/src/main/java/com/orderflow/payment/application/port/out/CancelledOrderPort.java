package com.orderflow.payment.application.port.out;

import java.util.UUID;

/** Sổ các đơn đã bị saga huỷ — đơn nằm ở đây thì không bao giờ được thu tiền. */
public interface CancelledOrderPort {

    /** @return {@code true} nếu đây là lần ghi đầu tiên (lần giao trùng trả về {@code false}) */
    boolean markCancelled(UUID orderId, String reason);

    boolean isCancelled(UUID orderId);
}
