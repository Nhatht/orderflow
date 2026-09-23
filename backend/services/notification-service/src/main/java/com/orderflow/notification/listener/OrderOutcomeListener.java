package com.orderflow.notification.listener;

import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.order.OrderCancelledEvent;
import com.orderflow.contracts.order.OrderConfirmedEvent;
import com.orderflow.notification.service.NotificationService;
import com.orderflow.notification.service.NotificationService.Kind;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Nghe kết cục saga. Group id riêng ({@code notification-service}): nhận bản
 * sao đầy đủ của {@code order.confirmed/cancelled}, song song với inventory —
 * hai service nghe cùng topic không tranh message của nhau.
 */
@Component
@RequiredArgsConstructor
public class OrderOutcomeListener {

    private final NotificationService notifications;

    @KafkaListener(topics = Topics.ORDER_CONFIRMED)
    public void onConfirmed(EventEnvelope<OrderConfirmedEvent> e) {
        MDC.put("correlationId", e.correlationId());
        try {
            notifications.notify(e.eventId(), e.payload().orderId(), e.payload().customerId(),
                    Kind.ORDER_CONFIRMED, null);
        } finally {
            MDC.remove("correlationId");
        }
    }

    @KafkaListener(topics = Topics.ORDER_CANCELLED)
    public void onCancelled(EventEnvelope<OrderCancelledEvent> e) {
        MDC.put("correlationId", e.correlationId());
        try {
            notifications.notify(e.eventId(), e.payload().orderId(), e.payload().customerId(),
                    Kind.ORDER_CANCELLED, e.payload().reason().name());
        } finally {
            MDC.remove("correlationId");
        }
    }
}
