package com.orderflow.order.application.service;

import com.orderflow.order.application.port.in.OrderSagaUseCase;
import com.orderflow.order.application.port.in.SagaTimeoutUseCase;
import com.orderflow.order.application.port.out.SagaStateRepositoryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tìm saga đứng ở AWAITING_PAYMENT quá {@code payment-timeout} rồi giao cho
 * orchestrator huỷ từng cái. Quyết định huỷ nằm ở orchestrator; class này chỉ
 * biết "ai đã chờ quá lâu".
 *
 * <p><b>Mốc thời gian là lúc lệnh {@code payment.requested} LÊN KAFKA</b>
 * ({@code outbox.published_at}), không phải lúc saga ghi lệnh vào outbox
 * ({@code saga_state.updated_at}). Bản đầu dùng {@code updated_at}; review tuần 8
 * chỉ ra: Kafka ngừng 4 phút → mọi đơn đang chờ tiền bị huỷ dù payment chưa hề
 * nhận lệnh, rồi khi Kafka sống lại, lệnh thu tiền và lệnh huỷ tới payment gần
 * như cùng lúc — thu tiền thường thắng → trừ tiền hàng loạt đơn đã huỷ. Giờ
 * đồng hồ chỉ chạy khi quả bóng thật sự đã ở phía payment.
 *
 * <p><b>An toàn khi nhiều instance cùng quét:</b> không cần ShedLock. Hai
 * instance cùng huỷ một saga thì một bên thắng, bên kia dính optimistic lock
 * (hoặc thấy saga đã COMPENSATING) và bỏ qua.
 */
@Slf4j
@Service
public class SagaTimeoutService implements SagaTimeoutUseCase {

    private final SagaStateRepositoryPort sagas;
    private final OrderSagaUseCase orchestrator;
    private final Duration paymentTimeout;
    private final int batchSize;

    public SagaTimeoutService(SagaStateRepositoryPort sagas,
                              OrderSagaUseCase orchestrator,
                              @Value("${orderflow.saga.payment-timeout:PT3M}") Duration paymentTimeout,
                              @Value("${orderflow.saga.timeout.batch-size:100}") int batchSize) {
        this.sagas = sagas;
        this.orchestrator = orchestrator;
        this.paymentTimeout = paymentTimeout;
        this.batchSize = batchSize;
    }

    @Override
    public int timeOutStalePayments(Instant now) {
        List<UUID> stale = sagas.findAwaitingPaymentRequestedBefore(now.minus(paymentTimeout), batchSize);

        int timedOut = 0;
        for (UUID orderId : stale) {
            try {
                if (orchestrator.onPaymentTimeout(orderId)) {
                    timedOut++;
                }
            } catch (RuntimeException e) {
                // Thua cuộc đua với payment.completed (optimistic lock), hoặc DB
                // chập chờn. Một đơn lỗi không được chặn cả lô; lần quét sau thử lại
                // — nếu tiền đã về thì saga không còn AWAITING_PAYMENT nữa.
                log.warn("saga-payment-timeout: could not time out order={}: {}", orderId, e.toString());
            }
        }
        if (timedOut > 0) {
            log.info("saga-payment-timeout: cancelled {} of {} stale saga(s)", timedOut, stale.size());
        }
        return timedOut;
    }
}
