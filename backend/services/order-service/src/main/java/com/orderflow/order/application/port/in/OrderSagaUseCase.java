package com.orderflow.order.application.port.in;

import com.orderflow.order.application.dto.SagaReply;

/**
 * Các phản hồi mà saga orchestrator phản ứng. Mỗi method idempotent theo
 * {@link SagaReply#eventId()} và bỏ qua (không ném lỗi) phản hồi đến sai lúc.
 */
public interface OrderSagaUseCase {

    void onStockReserved(SagaReply reply);

    void onStockReservationFailed(SagaReply reply);

    void onPaymentCompleted(SagaReply reply);

    void onPaymentFailed(SagaReply reply);

    void onStockReleased(SagaReply reply);

    /** Tuần 7: inventory tự nhả hàng vì phiếu giữ quá hạn. */
    void onReservationExpired(SagaReply reply);
}
