package com.orderflow.order.adapter.in.messaging;

import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.inventory.StockReleasedEvent;
import com.orderflow.contracts.inventory.StockReservationFailedEvent;
import com.orderflow.contracts.inventory.StockReservedEvent;
import com.orderflow.contracts.payment.PaymentCompletedEvent;
import com.orderflow.contracts.payment.PaymentFailedEvent;
import com.orderflow.order.application.dto.SagaReply;
import com.orderflow.order.application.port.in.OrderSagaUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Driving adapter: năm loại phản hồi từ inventory và payment → orchestrator.
 *
 * <p>Gom vào một class thay vì năm class listener: mỗi method chỉ vài dòng
 * dịch envelope thành {@link SagaReply}, không có logic. Logic nằm ở
 * {@code OrderSagaOrchestrator}.
 *
 * <p>Năm {@code @KafkaListener} = năm listener container độc lập, mỗi cái
 * một consumer group riêng trên topic riêng — cùng group-id
 * {@code order-service} nhưng Kafka quản lý offset theo từng topic nên chúng
 * không giẫm chân nhau.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaReplyListener {

    private final OrderSagaUseCase saga;

    @KafkaListener(topics = Topics.STOCK_RESERVED)
    public void onStockReserved(EventEnvelope<StockReservedEvent> e) {
        dispatch(e, e.payload().orderId(), null, saga::onStockReserved);
    }

    @KafkaListener(topics = Topics.STOCK_RESERVATION_FAILED)
    public void onStockReservationFailed(EventEnvelope<StockReservationFailedEvent> e) {
        dispatch(e, e.payload().orderId(), e.payload().reason().name(), saga::onStockReservationFailed);
    }

    @KafkaListener(topics = Topics.PAYMENT_COMPLETED)
    public void onPaymentCompleted(EventEnvelope<PaymentCompletedEvent> e) {
        dispatch(e, e.payload().orderId(), null, saga::onPaymentCompleted);
    }

    @KafkaListener(topics = Topics.PAYMENT_FAILED)
    public void onPaymentFailed(EventEnvelope<PaymentFailedEvent> e) {
        dispatch(e, e.payload().orderId(), e.payload().reason(), saga::onPaymentFailed);
    }

    @KafkaListener(topics = Topics.STOCK_RELEASED)
    public void onStockReleased(EventEnvelope<StockReleasedEvent> e) {
        dispatch(e, e.payload().orderId(), null, saga::onStockReleased);
    }

    private void dispatch(EventEnvelope<?> envelope, UUID orderId, String detail, Consumer<SagaReply> handler) {
        MDC.put("correlationId", envelope.correlationId());
        try {
            log.info("Received {} eventId={} order={}", envelope.eventType(), envelope.eventId(), orderId);
            handler.accept(new SagaReply(envelope.eventId(), envelope.correlationId(), orderId, detail));
        } finally {
            MDC.remove("correlationId");
        }
    }
}
