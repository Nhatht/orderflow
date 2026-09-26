package com.orderflow.order.application.port.in;

import com.orderflow.order.application.dto.SagaReply;

import java.util.UUID;

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

    /**
     * Saga timeout: chờ tiền quá hạn → huỷ đơn và đền bù. Không đến từ event nào
     * nên không có eventId — idempotent nhờ chính trạng thái saga: lần gọi thứ
     * hai thấy saga đã rời AWAITING_PAYMENT và bỏ qua.
     *
     * @return {@code true} nếu saga thật sự bị huỷ bởi lần gọi này
     */
    boolean onPaymentTimeout(UUID orderId);
}
