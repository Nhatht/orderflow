package com.orderflow.order.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.order.OrderCreatedEvent;
import com.orderflow.order.application.dto.OrderView;
import com.orderflow.order.application.dto.PlaceOrderCommand;
import com.orderflow.order.application.port.in.GetOrderQuery;
import com.orderflow.order.application.port.in.PlaceOrderUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TIÊU CHÍ NGHIỆM THU TUẦN 4 — phía producer. Từ tuần 5 event đi qua outbox,
 * nhưng những gì consumer nhìn thấy trên dây phải GIỮ NGUYÊN — test này
 * không sửa dòng nào về kỳ vọng, và đó chính là điều nó chứng minh.
 */
class OrderCreatedPublishingIT extends AbstractOrderIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final UUID KEO_DUA = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired PlaceOrderUseCase placeOrder;
    @Autowired GetOrderQuery getOrder;
    @Autowired ObjectMapper objectMapper;

    private PlaceOrderCommand twoKeoDua() {
        return new PlaceOrderCommand(UUID.randomUUID(), "VND", List.of(
                new PlaceOrderCommand.Item(KEO_DUA, "Kẹo dừa Bến Tre", 2, new BigDecimal("45000"))));
    }

    @Test
    @DisplayName("Đặt đơn → order.created xuất hiện trên Kafka với key = orderId và payload đầy đủ")
    void placingOrderPublishesOrderCreated() throws Exception {
        try (var consumer = orderCreatedConsumer()) {
            OrderView order = placeOrder.placeOrder(twoKeoDua());

            ConsumerRecord<String, String> record = awaitRecordWithKey(consumer, order.id().toString(), TIMEOUT);

            EventEnvelope<OrderCreatedEvent> envelope =
                    objectMapper.readValue(record.value(), new TypeReference<>() {});

            assertThat(envelope.eventType()).isEqualTo(OrderCreatedEvent.TYPE);
            assertThat(envelope.aggregateId()).isEqualTo(order.id().toString());
            assertThat(envelope.correlationId()).isNotBlank();

            OrderCreatedEvent payload = envelope.payload();
            assertThat(payload.orderId()).isEqualTo(order.id());
            assertThat(payload.customerId()).isEqualTo(order.customerId());
            assertThat(payload.totalAmount()).isEqualByComparingTo("90000");
            assertThat(payload.items()).singleElement().satisfies(item -> {
                assertThat(item.productId()).isEqualTo(KEO_DUA);
                assertThat(item.quantity()).isEqualTo(2);
            });
        }
    }

    @Test
    @DisplayName("Không có header __TypeId__ — tên class Java không rò rỉ lên dây")
    void noJavaTypeHeaderOnTheWire() {
        try (var consumer = orderCreatedConsumer()) {
            OrderView order = placeOrder.placeOrder(twoKeoDua());

            ConsumerRecord<String, String> record = awaitRecordWithKey(consumer, order.id().toString(), TIMEOUT);

            assertThat(record.headers().lastHeader("__TypeId__")).isNull();
        }
    }

    @Test
    @DisplayName("Khi event tới nơi, đơn ĐÃ nằm trong database")
    void orderExistsWhenEventArrives() {
        try (var consumer = orderCreatedConsumer()) {
            OrderView order = placeOrder.placeOrder(twoKeoDua());

            awaitRecordWithKey(consumer, order.id().toString(), TIMEOUT);

            // Poller chỉ thấy dòng outbox ĐÃ COMMIT — nên event không bao giờ
            // tới nơi trước đơn. Tuần 4 bảo đảm điều này bằng thứ tự lời gọi;
            // tuần 5 bảo đảm bằng chính cơ chế isolation của database.
            assertThat(getOrder.getById(order.id()).status()).isEqualTo("PENDING");
        }
    }
}
