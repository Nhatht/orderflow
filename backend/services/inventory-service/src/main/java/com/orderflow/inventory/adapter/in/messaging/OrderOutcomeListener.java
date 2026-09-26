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

import java.util.EnumSet;
import java.util.Set;

/**
 * Driving adapter: kết cục của saga → chốt hoặc nhả hàng đang giữ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderOutcomeListener {

    /**
     * Những lý do huỷ mà saga đang ở COMPENSATING và CHỜ {@code stock.released}.
     * Thiếu một lý do ở đây là saga của lý do đó treo mãi — thêm lý do huỷ mới
     * vào contract thì PHẢI xem lại tập này. Hai lý do còn lại không chờ:
     * STOCK_UNAVAILABLE (chưa giữ gì) và RESERVATION_EXPIRED (saga FAILED ngay).
     */
    private static final Set<OrderCancelledEvent.Reason> SAGA_AWAITS_RELEASE = EnumSet.of(
            OrderCancelledEvent.Reason.PAYMENT_DECLINED,
            OrderCancelledEvent.Reason.PAYMENT_TIMEOUT);

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
            boolean sagaAwaitsAck = SAGA_AWAITS_RELEASE.contains(envelope.payload().reason());
            settleOrderStock.releaseForOrder(envelope.eventId(), envelope.correlationId(),
                    envelope.payload().orderId(), sagaAwaitsAck);
        } finally {
            MDC.remove("correlationId");
        }
    }
}
