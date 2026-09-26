package com.orderflow.order.adapter.in.scheduling;

import com.orderflow.order.application.port.in.SagaTimeoutUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * DRIVING ADAPTER dạng hẹn giờ — "đồng hồ" gọi vào saga, giống
 * {@code ReservationExpiryJob} bên inventory.
 */
@Component
@RequiredArgsConstructor
public class SagaTimeoutJob {

    private final SagaTimeoutUseCase sagaTimeout;

    /**
     * <b>{@code initialDelay} — vì sao KHÔNG quét ngay lúc khởi động.</b>
     * order-service vừa sập 10 phút rồi sống lại: trong Kafka có thể còn
     * {@code payment.completed} của khách đã trả tiền ĐÚNG HẠN, chỉ là chưa ai
     * đọc. Quét ngay thì job thấy "chờ tiền 11 phút" và huỷ oan đơn đó. Chờ 2
     * phút cho listener đọc hết hàng đợi rồi mới được quyết định.
     *
     * <p>Đây là ƯỚC LƯỢNG: hàng đợi dài hơn 2 phút thì vẫn có thể huỷ oan. Cách
     * chặt hơn là kiểm consumer lag trước khi quét; xem {@code docs/SAGA-TIMEOUT.md}.
     *
     * <p><b>ĐIỂM YẾU ĐÃ BIẾT — chưa có hoàn tiền tự động.</b> Huỷ oan một đơn mà
     * tiền đã trừ thì chỉ có log {@code LATE PAYMENT ... REFUND REQUIRED}.
     *
     * <p>initialDelay chỉ che lúc ORDER-service khởi động lại. Trường hợp
     * PAYMENT-service sập/tụt lại quá 3 phút được che ở phía payment: nó nghe
     * {@code order.cancelled}, ghi bia mộ, và không gọi cổng cho đơn đã huỷ
     * ({@code CancelOrderPaymentService}). Còn khe hở nhỏ: lệnh huỷ tới đúng lúc
     * lời gọi cổng đang bay.
     */
    @Scheduled(initialDelayString = "${orderflow.saga.timeout.initial-delay:PT2M}",
               fixedDelayString = "${orderflow.saga.timeout.scan-interval:PT30S}")
    public void run() {
        sagaTimeout.timeOutStalePayments(Instant.now());
    }
}
