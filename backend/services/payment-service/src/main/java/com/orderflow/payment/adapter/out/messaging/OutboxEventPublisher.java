package com.orderflow.payment.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.payment.PaymentCompletedEvent;
import com.orderflow.contracts.payment.PaymentFailedEvent;
import com.orderflow.payment.application.port.out.EventPublisherPort;
import com.orderflow.payment.domain.model.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi kết quả thanh toán vào outbox — xem {@code OutboxEventPublisher} của
 * order-service để biết vì sao MANDATORY.
 */
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher implements EventPublisherPort {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishPaymentCompleted(Payment payment, String correlationId) {
        var payload = new PaymentCompletedEvent(payment.orderId(), payment.id(), payment.amount(), payment.currency());
        append(Topics.PAYMENT_COMPLETED,
                EventEnvelope.of(PaymentCompletedEvent.TYPE, payment.orderId().toString(), correlationId, payload));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishPaymentFailed(Payment payment, String correlationId) {
        var payload = new PaymentFailedEvent(payment.orderId(), payment.id(), payment.failureReason());
        append(Topics.PAYMENT_FAILED,
                EventEnvelope.of(PaymentFailedEvent.TYPE, payment.orderId().toString(), correlationId, payload));
    }

    /** Key = orderId, giống mọi service khác — xem {@code KafkaConfig} của order-service. */
    private void append(String topic, EventEnvelope<?> envelope) {
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + envelope.eventType(), e);
        }
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, topic, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """,
                envelope.eventId(), "Order", envelope.aggregateId(), envelope.eventType(), topic, json);
    }
}
