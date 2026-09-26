package com.orderflow.order.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.order.OrderCancelledEvent;
import com.orderflow.contracts.order.OrderConfirmedEvent;
import com.orderflow.contracts.order.OrderCreatedEvent;
import com.orderflow.contracts.payment.PaymentRequestedEvent;
import com.orderflow.order.application.port.out.EventPublisherPort;
import com.orderflow.order.domain.model.Order;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * "Phát" event bằng cách GHI vào bảng outbox — không đụng tới Kafka.
 *
 * <p>Thay cho {@code KafkaEventPublisher} của tuần 4. {@link EventPublisherPort}
 * giữ nguyên chữ ký, nên tầng application chỉ đổi MỘT chỗ: gọi publisher bên
 * trong transaction thay vì sau nó.
 *
 * <p><b>{@code Propagation.MANDATORY} là lưới an toàn quan trọng nhất ở đây.</b>
 * Cả pattern chỉ đúng khi dòng outbox được ghi CÙNG transaction với dữ liệu
 * nghiệp vụ. Ai đó sau này lỡ gọi publisher sau khi commit (đúng kiểu code
 * tuần 4) thì dòng outbox nằm trong transaction RIÊNG — và lỗi dual write quay
 * lại, lặng lẽ. MANDATORY biến sai lầm đó thành exception ngay lần chạy đầu:
 * không có transaction đang mở → {@code IllegalTransactionStateException}.
 */
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher implements EventPublisherPort {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final OutboxTracing tracing;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishOrderCreated(Order order) {
        var payload = new OrderCreatedEvent(
                order.id(),
                order.customerId(),
                order.currency(),
                order.totalAmount().amount(),
                order.items().stream()
                        .map(i -> new OrderCreatedEvent.Item(
                                i.productId(), i.productName(), i.quantity(), i.unitPrice().amount()))
                        .toList());

        // correlationId sinh ở RÌA hệ thống (API Gateway) và tới đây qua MDC —
        // xem CorrelationIdFilter. Không có (gọi từ test, từ job) thì tự sinh.
        String correlationId = MDC.get("correlationId");
        var envelope = EventEnvelope.of(
                OrderCreatedEvent.TYPE, order.id().toString(),
                correlationId != null ? correlationId : UUID.randomUUID().toString(), payload);

        append("Order", Topics.ORDER_CREATED, envelope);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishPaymentRequested(Order order, String correlationId) {
        var payload = new PaymentRequestedEvent(
                order.id(), order.customerId(), order.totalAmount().amount(), order.currency());
        append("Order", Topics.PAYMENT_REQUESTED,
                EventEnvelope.of(PaymentRequestedEvent.TYPE, order.id().toString(), correlationId, payload));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishOrderConfirmed(Order order, String correlationId) {
        append("Order", Topics.ORDER_CONFIRMED,
                EventEnvelope.of(OrderConfirmedEvent.TYPE, order.id().toString(), correlationId,
                        new OrderConfirmedEvent(order.id(), order.customerId())));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishOrderCancelled(Order order, CancellationReason reason, String correlationId) {
        append("Order", Topics.ORDER_CANCELLED,
                EventEnvelope.of(OrderCancelledEvent.TYPE, order.id().toString(), correlationId,
                        new OrderCancelledEvent(order.id(), order.customerId(), OrderCancelledEvent.Reason.valueOf(reason.name()))));
    }

    /**
     * Serialize NGAY BÂY GIỜ, lúc ghi — không để poller serialize lúc gửi.
     * Event là ảnh chụp sự việc tại thời điểm nó xảy ra; nếu poller đọc lại
     * domain object lúc gửi thì có thể gửi đi trạng thái của mấy giây sau.
     */
    private void append(String aggregateType, String topic, EventEnvelope<?> envelope) {
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            // Lỗi lập trình, không phải lỗi hạ tầng — để nó làm rollback cả đơn.
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
