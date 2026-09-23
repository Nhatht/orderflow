package com.orderflow.inventory.adapter.out.messaging;

import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.inventory.StockReservationFailedEvent;
import com.orderflow.contracts.inventory.StockReservedEvent;
import com.orderflow.inventory.application.dto.ReservationOutcome;
import com.orderflow.inventory.application.port.out.EventPublisherPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Dịch kết quả giữ hàng sang event contract rồi gửi Kafka.
 *
 * <p>Đây là chỗ DUY NHẤT trong inventory biết đến định dạng trên dây. Tầng
 * application nói bằng {@link ReservationOutcome} của riêng nó; đổi contract
 * thì chỉ sửa file này.
 *
 * <p>Cùng lỗ hổng dual write như order-service — xem {@code KafkaEventPublisher}
 * bên đó. Tuần 5 thay bằng Outbox.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventPublisher implements EventPublisherPort {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void publishStockReserved(ReservationOutcome.Reserved outcome, String correlationId) {
        var payload = new StockReservedEvent(
                outcome.orderId(),
                outcome.reservations().stream()
                        .map(r -> new StockReservedEvent.ReservedItem(r.id(), r.productId(), r.quantity()))
                        .toList(),
                outcome.expiresAt());

        send(Topics.STOCK_RESERVED,
                EventEnvelope.of(StockReservedEvent.TYPE, outcome.orderId().toString(), correlationId, payload));
    }

    @Override
    public void publishStockReservationFailed(ReservationOutcome.Rejected outcome, String correlationId) {
        var payload = new StockReservationFailedEvent(
                outcome.orderId(),
                StockReservationFailedEvent.Reason.valueOf(outcome.reason().name()),
                outcome.shortages().stream()
                        .map(s -> new StockReservationFailedEvent.Shortage(s.productId(), s.requested(), s.available()))
                        .toList());

        send(Topics.STOCK_RESERVATION_FAILED,
                EventEnvelope.of(StockReservationFailedEvent.TYPE, outcome.orderId().toString(), correlationId, payload));
    }

    /**
     * Key = orderId (aggregateId), giống order-service. Mọi event về cùng một
     * đơn — dù do service nào phát — đều theo key đó, nên ở phía đọc chúng
     * luôn nằm cùng partition của từng topic và giữ đúng thứ tự.
     */
    private void send(String topic, EventEnvelope<?> envelope) {
        kafkaTemplate.send(topic, envelope.aggregateId(), envelope)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("LOST EVENT {} {} for order {} — saga will stall until week-5 outbox exists",
                                envelope.eventType(), envelope.eventId(), envelope.aggregateId(), ex);
                    } else {
                        var meta = result.getRecordMetadata();
                        log.info("Published {} eventId={} order={} correlationId={} -> {}-{}@{}",
                                envelope.eventType(), envelope.eventId(), envelope.aggregateId(),
                                envelope.correlationId(), meta.topic(), meta.partition(), meta.offset());
                    }
                });
    }
}
