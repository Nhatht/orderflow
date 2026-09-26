package com.orderflow.order.application.service;

import com.orderflow.order.application.dto.SagaReply;
import com.orderflow.order.application.port.in.OrderSagaUseCase;
import com.orderflow.order.application.port.out.EventPublisherPort;
import com.orderflow.order.application.port.out.EventPublisherPort.CancellationReason;
import com.orderflow.order.application.port.out.OrderRepositoryPort;
import com.orderflow.order.application.port.out.ProcessedEventPort;
import com.orderflow.order.application.port.out.SagaStateRepositoryPort;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderSaga;
import com.orderflow.order.domain.model.SagaStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * SAGA ORCHESTRATOR — trái tim của dự án.
 *
 * <p>Nghe phản hồi từ inventory và payment, quyết định bước tiếp theo, phát
 * lệnh kế tiếp. Không service nào khác biết toàn bộ luồng; chỉ class này biết.
 * Đó là khác biệt với choreography, nơi mỗi service tự phản ứng với event của
 * nhau và "luồng" chỉ tồn tại ngầm trong đầu người thiết kế.
 *
 * <pre>
 *   order.created ─▶ [inventory] ─▶ stock.reserved ─▶ payment.requested ─▶ [payment]
 *                                                                          │
 *        order.confirmed ◀── payment.completed ◀──────────────────────────┤
 *        order.cancelled ◀── payment.failed    ◀──────────────────────────┘
 *              │
 *              └─▶ [inventory nhả hàng] ─▶ stock.released ─▶ COMPENSATED
 * </pre>
 *
 * <h2>Mỗi phản hồi = một transaction</h2>
 * Sổ {@code processed_events} + trạng thái đơn + trạng thái saga + nhật ký
 * bước + lệnh kế tiếp trong outbox — tất cả commit cùng lúc. Service chết ở
 * bất kỳ đâu thì hoặc bước đó xong trọn vẹn, hoặc như chưa xảy ra và Kafka
 * giao lại phản hồi.
 *
 * <h2>Phản hồi đến sai lúc: BỎ QUA, không ném lỗi</h2>
 * Ví dụ {@code payment.completed} đến cho một đơn đã huỷ (late response).
 * Ném exception thì Kafka giao lại mãi mãi — message đó chặn luôn mọi phản
 * hồi đứng sau nó trong cùng partition. Thay vào đó: ghi sổ, log cảnh báo,
 * trả về bình thường. Trạng thái saga là nguồn sự thật; phản hồi không khớp
 * với nó là phản hồi cũ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSagaOrchestrator implements OrderSagaUseCase {

    private final OrderRepositoryPort orders;
    private final SagaStateRepositoryPort sagas;
    private final ProcessedEventPort processedEvents;
    private final EventPublisherPort eventPublisher;
    private final TransactionTemplate tx;

    @Override
    public void onStockReserved(SagaReply reply) {
        handle(reply, "saga-stock-reserved", SagaStatus.STARTED, (order, saga) -> {
            order.markStockReserved();
            saga.stockReserved();
            eventPublisher.publishPaymentRequested(order, reply.correlationId());
        });
    }

    @Override
    public void onStockReservationFailed(SagaReply reply) {
        handle(reply, "saga-stock-failed", SagaStatus.STARTED, (order, saga) -> {
            order.cancel();
            saga.stockUnavailable(reply.detail());
            // Inventory chưa giữ gì, nhưng vẫn phát: notification cần báo khách,
            // và inventory xử lý "nhả khi không có gì để nhả" là không làm gì.
            eventPublisher.publishOrderCancelled(order, CancellationReason.STOCK_UNAVAILABLE, reply.correlationId());
        });
    }

    @Override
    public void onPaymentCompleted(SagaReply reply) {
        handle(reply, "saga-payment-completed", SagaStatus.AWAITING_PAYMENT, (order, saga) -> {
            order.markPaid();
            order.confirm();
            saga.paymentCompleted();
            eventPublisher.publishOrderConfirmed(order, reply.correlationId());
        });
    }

    @Override
    public void onPaymentFailed(SagaReply reply) {
        handle(reply, "saga-payment-failed", SagaStatus.AWAITING_PAYMENT, (order, saga) -> {
            order.cancel();
            saga.paymentFailed(reply.detail());
            // ĐỀN BÙ: hàng đã giữ ở bước 1 phải được trả về kho.
            eventPublisher.publishOrderCancelled(order, CancellationReason.PAYMENT_DECLINED, reply.correlationId());
        });
    }

    @Override
    public void onStockReleased(SagaReply reply) {
        handle(reply, "saga-stock-released", SagaStatus.COMPENSATING,
                (order, saga) -> saga.stockReleased());
    }

    /**
     * Phiếu giữ hàng hết hạn khi saga đang chờ thanh toán → huỷ đơn.
     *
     * <p>Chỉ xử lý ở AWAITING_PAYMENT. Ở STARTED (order-service tụt lại hơn 3
     * phút, chưa kịp đọc stock.reserved) phản hồi này bị coi là cũ và bỏ qua —
     * trường hợp hiếm, ghi thành điểm yếu đã biết thay vì làm phức tạp thêm luật
     * chuyển trạng thái.
     */
    @Override
    public void onReservationExpired(SagaReply reply) {
        handle(reply, "saga-reservation-expired", SagaStatus.AWAITING_PAYMENT, (order, saga) -> {
            order.cancel();
            saga.reservationExpired();
            eventPublisher.publishOrderCancelled(order, CancellationReason.RESERVATION_EXPIRED, reply.correlationId());
        });
    }

    /**
     * SAGA TIMEOUT (phương án A trong {@code docs/SAGA-TIMEOUT.md}) — saga giữ
     * đồng hồ chính, ngắn hơn TTL phiếu giữ hàng, nên nó quyết trước job hết
     * hạn của inventory và hai bên không còn đua nhau.
     *
     * <p><b>Đua với {@code payment.completed}</b> vẫn có thể xảy ra, nhưng giờ nằm
     * trên MỘT dòng {@code saga_state}: cả hai cùng đọc version N, bên ghi sau
     * nhận 0 dòng → {@code OptimisticLockingFailureException} → rollback.
     * Listener thua thì Kafka giao lại → thấy COMPENSATING → LATE PAYMENT. Job
     * thua thì lần quét sau saga đã COMPLETED, không còn trong danh sách. Bên
     * nào thắng cũng ra kết quả nhất quán.
     */
    @Override
    public boolean onPaymentTimeout(UUID orderId) {
        Boolean timedOut = tx.execute(status -> advance(orderId, "saga-payment-timeout", SagaStatus.AWAITING_PAYMENT,
                (order, saga) -> {
                    order.cancel();
                    saga.paymentTimedOut();
                    // ĐỀN BÙ: hàng vẫn đang được giữ (TTL phiếu dài hơn) — phải nhả.
                    // correlationId: saga_state không lưu correlationId của request
                    // gốc; tự sinh có tiền tố như job hết hạn bên inventory.
                    eventPublisher.publishOrderCancelled(order, CancellationReason.PAYMENT_TIMEOUT,
                            "saga-timeout-" + UUID.randomUUID());
                },
                // Không phải lỗi: tiền vừa về, hoặc instance khác vừa huỷ xong.
                current -> log.info("saga-payment-timeout: order={} saga is already {}, nothing to do",
                        orderId, current)));
        return Boolean.TRUE.equals(timedOut);
    }

    // -------------------------------------------------------------------------

    private void handle(SagaReply reply, String operation, SagaStatus expected,
                        BiConsumer<Order, OrderSaga> step) {
        tx.executeWithoutResult(status -> {
            if (!processedEvents.markProcessed(reply.eventId(), operation)) {
                log.info("{}: duplicate delivery eventId={} order={}, ignoring",
                        operation, reply.eventId(), reply.orderId());
                return;
            }
            advance(reply.orderId(), operation, expected, step,
                    current -> warnOutOfOrder(operation, reply, current, expected));
        });
    }

    /**
     * Load → kiểm trạng thái → chạy bước → lưu. Phải gọi TRONG transaction.
     * Tách khỏi {@link #handle} vì saga timeout không có event (nên không có
     * eventId để ghi sổ {@code processed_events}) nhưng dùng đúng khuôn này.
     *
     * @return {@code true} nếu bước đã được áp dụng
     */
    private boolean advance(UUID orderId, String operation, SagaStatus expected,
                            BiConsumer<Order, OrderSaga> step, Consumer<SagaStatus> onUnexpectedStatus) {
        var order = orders.findById(orderId);
        var saga = sagas.findByOrderId(orderId);
        if (order.isEmpty() || saga.isEmpty()) {
            log.warn("{}: no order/saga for order={}, ignoring", operation, orderId);
            return false;
        }

        SagaStatus current = saga.get().status();
        if (current != expected) {
            onUnexpectedStatus.accept(current);
            return false;
        }

        step.accept(order.get(), saga.get());
        orders.save(order.get());
        sagas.save(saga.get());

        log.info("{}: order={} saga {} -> {}", operation, orderId, current, saga.get().status());
        return true;
    }

    private void warnOutOfOrder(String operation, SagaReply reply, SagaStatus current, SagaStatus expected) {
        if ("saga-payment-completed".equals(operation) && current != SagaStatus.COMPLETED) {
            // LATE RESPONSE: tiền đã thu cho một đơn đã huỷ. Đây là bẫy đã ghi
            // trong docs/PATTERNS.md. Cách xử lý đúng là HOÀN TIỀN tự động —
            // chưa làm; hiện chỉ để lại dấu vết rõ ràng cho job đối soát.
            log.error("LATE PAYMENT: order={} was paid while saga is {} — REFUND REQUIRED (eventId={})",
                    reply.orderId(), current, reply.eventId());
        } else {
            log.warn("{}: order={} saga is {} (expected {}), stale reply eventId={} ignored",
                    operation, reply.orderId(), current, expected, reply.eventId());
        }
    }
}
