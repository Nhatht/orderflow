package com.orderflow.order.application.port.out;

import com.orderflow.order.domain.model.OrderSaga;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SagaStateRepositoryPort {

    /**
     * Lưu trạng thái + GHI THÊM các dòng trong {@link OrderSaga#pendingLog()}.
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException nếu có
     *         người khác đã sửa saga này kể từ lúc đọc lên
     */
    void save(OrderSaga saga);

    Optional<OrderSaga> findByOrderId(UUID orderId);

    /**
     * Saga chờ tiền mà lệnh {@code payment.requested} của nó ĐÃ LÊN KAFKA trước
     * {@code sentBefore} — cũ nhất trước.
     *
     * <p>Tính giờ từ lúc lệnh thật sự rời đi, KHÔNG từ lúc saga ghi lệnh vào
     * outbox: Kafka hay poller ngừng vài phút thì payment chưa hề nhận lệnh, huỷ
     * lúc đó là phạt khách vì lỗi của chính hệ thống (review tuần 8).
     */
    List<UUID> findAwaitingPaymentRequestedBefore(Instant sentBefore, int limit);

    /** Toàn bộ nhật ký các bước, theo thứ tự xảy ra — cho saga timeline. */
    List<StepRecord> history(UUID orderId);

    record StepRecord(String step, String outcome, String detail, Instant occurredAt) {}
}
