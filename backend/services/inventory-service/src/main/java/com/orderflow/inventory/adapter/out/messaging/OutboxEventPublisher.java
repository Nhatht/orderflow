package com.orderflow.inventory.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.inventory.StockReservationFailedEvent;
import com.orderflow.contracts.inventory.StockReservedEvent;
import com.orderflow.inventory.application.dto.ReservationOutcome;
import com.orderflow.inventory.application.port.out.EventPublisherPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dịch kết quả giữ hàng sang event contract rồi GHI vào bảng outbox.
 *
 * <p>Đây vẫn là chỗ DUY NHẤT trong inventory biết định dạng trên dây. Việc
 * đẩy lên Kafka là của {@link OutboxPoller}.
 *
 * <p>{@code MANDATORY}: bắt buộc chạy trong transaction đang giữ hàng — xem
 * giải thích ở {@code OutboxEventPublisher} của order-service.
 */
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher implements EventPublisherPort {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishStockReserved(ReservationOutcome.Reserved outcome, String correlationId) {
        var payload = new StockReservedEvent(
                outcome.orderId(),
                outcome.reservations().stream()
                        .map(r -> new StockReservedEvent.ReservedItem(r.id(), r.productId(), r.quantity()))
                        .toList(),
                outcome.expiresAt());

        append(Topics.STOCK_RESERVED,
                EventEnvelope.of(StockReservedEvent.TYPE, outcome.orderId().toString(), correlationId, payload));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishStockReservationFailed(ReservationOutcome.Rejected outcome, String correlationId) {
        var payload = new StockReservationFailedEvent(
                outcome.orderId(),
                StockReservationFailedEvent.Reason.valueOf(outcome.reason().name()),
                outcome.shortages().stream()
                        .map(s -> new StockReservationFailedEvent.Shortage(s.productId(), s.requested(), s.available()))
                        .toList());

        append(Topics.STOCK_RESERVATION_FAILED,
                EventEnvelope.of(StockReservationFailedEvent.TYPE, outcome.orderId().toString(), correlationId, payload));
    }

    /**
     * Key = orderId (aggregateId) — mọi event về cùng một đơn, dù do service
     * nào phát, đều theo key đó và giữ đúng thứ tự trong partition.
     */
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
                envelope.eventId(), "Order", envelope.aggregateId(),
                envelope.eventType(), topic, json);
    }
}
