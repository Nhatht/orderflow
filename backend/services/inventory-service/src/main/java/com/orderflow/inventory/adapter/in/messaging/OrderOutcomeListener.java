package com.orderflow.inventory.adapter.in.messaging;

import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.order.OrderCancelledEvent;
import com.orderflow.contracts.order.OrderConfirmedEvent;
import com.orderflow.inventory.application.port.in.SettleOrderStockUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Driving adapter: kết cục của saga → chốt hoặc nhả hàng đang giữ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderOutcomeListener {

    private final SettleOrderStockUseCase settleOrderStock;

    @KafkaListener(topics = Topics.ORDER_CONFIRMED)
    public void onOrderConfirmed(EventEnvelope<OrderConfirmedEvent> envelope) {
        MDC.put("correlationId", envelope.correlationId());
        try {
            log.info("Received {} eventId={} order={}", envelope.eventType(), envelope.eventId(),
                    envelope.payload().orderId());
            settleOrderStock.confirmForOrder(envelope.eventId(), envelope.correlationId(), envelope.payload().orderId());
        } finally {
            MDC.remove("correlationId");
        }
    }

    @KafkaListener(topics = Topics.ORDER_CANCELLED)
    public void onOrderCancelled(EventEnvelope<OrderCancelledEvent> envelope) {
        MDC.put("correlationId", envelope.correlationId());
        try {
            log.info("Received {} eventId={} order={} reason={}", envelope.eventType(), envelope.eventId(),
                    envelope.payload().orderId(), envelope.payload().reason());
            // Chỉ huỷ vì thanh toán thì saga mới đang chờ xác nhận đền bù.
            boolean sagaAwaitsAck = envelope.payload().reason() == OrderCancelledEvent.Reason.PAYMENT_DECLINED;
            settleOrderStock.releaseForOrder(envelope.eventId(), envelope.correlationId(),
                    envelope.payload().orderId(), sagaAwaitsAck);
        } finally {
            MDC.remove("correlationId");
        }
    }
}
