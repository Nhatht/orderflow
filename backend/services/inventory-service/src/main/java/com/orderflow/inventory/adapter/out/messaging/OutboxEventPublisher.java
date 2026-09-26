package com.orderflow.inventory.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.inventory.StockChangedEvent;
import com.orderflow.contracts.inventory.StockReleasedEvent;
import com.orderflow.contracts.inventory.StockReservationExpiredEvent;
import com.orderflow.contracts.inventory.StockReservationFailedEvent;
import com.orderflow.contracts.inventory.StockReservedEvent;
import com.orderflow.inventory.application.dto.ReservationOutcome;
import com.orderflow.inventory.application.dto.ReservationView;
import com.orderflow.inventory.application.port.out.EventPublisherPort;
import com.orderflow.inventory.domain.model.Stock;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

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
    private final OutboxTracing tracing;

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

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishStockReleased(UUID orderId, List<ReservationView> released, String correlationId) {
        var payload = new StockReleasedEvent(orderId, released.stream()
                .map(r -> new StockReleasedEvent.ReleasedItem(r.productId(), r.quantity()))
                .toList());
        append(Topics.STOCK_RELEASED,
                EventEnvelope.of(StockReleasedEvent.TYPE, orderId.toString(), correlationId, payload));
    }

    /**
     * correlationId tự sinh, tiền tố {@code expiry-}: phiếu giữ hàng không lưu
     * correlationId của luồng gốc, nên không nối được vào luồng cũ. Tiền tố giúp
     * grep log phân biệt ngay "event do job tự phát" với event đáp lại lệnh.
     * (Muốn nối được thì phải lưu correlationId vào stock_reservations — chưa đáng.)
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishStockReservationExpired(UUID orderId, List<ReservationView> expired) {
        var payload = new StockReservationExpiredEvent(orderId, expired.stream()
                .map(r -> new StockReservationExpiredEvent.ExpiredItem(r.productId(), r.quantity()))
                .toList());
        append(Topics.STOCK_RESERVATION_EXPIRED,
                EventEnvelope.of(StockReservationExpiredEvent.TYPE, orderId.toString(),
                        "expiry-" + UUID.randomUUID(), payload));
    }

    /**
     * Key = productId: mọi thay đổi của một sản phẩm vào cùng partition, xử lý
     * theo đúng thứ tự. correlationId lấy từ luồng đang chạy nếu có (MDC), không
     * thì tự sinh — thay đổi tồn kho có thể do job hết hạn, không có request gốc.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishStockChanged(Stock stock) {
        String correlationId = MDC.get("correlationId");
        append(Topics.STOCK_CHANGED, "Stock",
                EventEnvelope.of(StockChangedEvent.TYPE, stock.productId().toString(),
                        correlationId != null ? correlationId : "stock-" + UUID.randomUUID(),
                        new StockChangedEvent(stock.productId(), stock.availableQty(), stock.reservedQty())));
    }

    private void append(String topic, EventEnvelope<?> envelope) {
        append(topic, "Order", envelope);
    }

    /**
     * Key = aggregateId. Với event về đơn là orderId — mọi event về cùng một
     * đơn, dù do service nào phát, đều theo key đó và giữ đúng thứ tự trong partition.
     */
    private void append(String topic, String aggregateType, EventEnvelope<?> envelope) {
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + envelope.eventType(), e);
        }

        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, topic, payload, trace_parent)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                envelope.eventId(), aggregateType, envelope.aggregateId(),
                envelope.eventType(), topic, json, tracing.currentTraceParent());
    }
}
