package com.orderflow.payment.adapter.in.messaging;

import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.order.OrderCancelledEvent;
import com.orderflow.payment.application.port.in.CancelOrderPaymentUseCase;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Driving adapter: {@code order.cancelled} → ghi bia mộ để không thu tiền đơn
 * đã huỷ. Group {@code payment-service} nhận bản sao riêng của topic, song song
 * với inventory và notification.
 */
@Component
@RequiredArgsConstructor
public class OrderCancelledListener {

    private final CancelOrderPaymentUseCase cancelOrderPayment;

    @KafkaListener(topics = Topics.ORDER_CANCELLED)
    public void onOrderCancelled(EventEnvelope<OrderCancelledEvent> envelope) {
        MDC.put("correlationId", envelope.correlationId());
        try {
            cancelOrderPayment.onOrderCancelled(envelope.payload().orderId(), envelope.payload().reason().name());
        } finally {
            MDC.remove("correlationId");
        }
    }
}
