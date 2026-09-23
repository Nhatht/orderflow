package com.orderflow.contracts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderflow.contracts.inventory.StockReservationFailedEvent;
import com.orderflow.contracts.order.OrderCreatedEvent;
import com.orderflow.contracts.payment.PaymentRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Khoá cứng ĐỊNH DẠNG TRÊN DÂY của event.
 *
 * <p>Contract là thứ hai service độc lập cùng dựa vào. Đổi tên một field ở
 * đây thì producer vẫn biên dịch, consumer vẫn biên dịch, và cả hai cùng chạy
 * — chỉ có dữ liệu lặng lẽ biến thành {@code null}. Test này bắt lỗi đó ngay
 * lúc build thay vì lúc chạy thật.
 */
class EventEnvelopeSerializationTest {

    /** Cấu hình giống ObjectMapper mà Spring Boot và Spring Kafka dựng sẵn. */
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final UUID orderId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private final UUID productId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private EventEnvelope<OrderCreatedEvent> sampleOrderCreated() {
        var payload = new OrderCreatedEvent(
                orderId, UUID.randomUUID(), "VND", new BigDecimal("90000.0000"),
                List.of(new OrderCreatedEvent.Item(productId, "Kẹo dừa Bến Tre", 2, new BigDecimal("45000.0000"))));
        return EventEnvelope.of(OrderCreatedEvent.TYPE, orderId.toString(), "corr-1", payload);
    }

    @Test
    @DisplayName("Round-trip giữ nguyên kiểu payload nhờ generic TypeReference")
    void roundTripKeepsTypedPayload() throws Exception {
        var original = sampleOrderCreated();

        String json = mapper.writeValueAsString(original);
        EventEnvelope<OrderCreatedEvent> restored =
                mapper.readValue(json, new TypeReference<>() {});

        assertThat(restored).isEqualTo(original);
        // Không phải LinkedHashMap — đây là điểm mấu chốt của thiết kế generic
        assertThat(restored.payload()).isInstanceOf(OrderCreatedEvent.class);
        assertThat(restored.payload().items().getFirst().productName()).isEqualTo("Kẹo dừa Bến Tre");
    }

    @Test
    @DisplayName("Tiền đi dạng chuỗi, giữ đủ 4 chữ số thập phân")
    void moneyIsSerializedAsString() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(sampleOrderCreated()));

        JsonNode total = json.at("/payload/totalAmount");
        assertThat(total.isTextual()).as("totalAmount phải là chuỗi, không phải số").isTrue();
        assertThat(total.asText()).isEqualTo("90000.0000");
        assertThat(json.at("/payload/items/0/unitPrice").asText()).isEqualTo("45000.0000");
    }

    @Test
    @DisplayName("Thời gian đi dạng ISO-8601, không phải epoch number")
    void timestampIsIso8601() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(sampleOrderCreated()));

        assertThat(json.get("occurredAt").isTextual()).isTrue();
        assertThat(json.get("occurredAt").asText()).endsWith("Z");
    }

    @Test
    @DisplayName("Consumer đời cũ bỏ qua field mới mà producer thêm vào")
    void toleratesUnknownFields() throws Exception {
        // Producer nâng cấp trước, thêm field "promotionCode" — consumer chưa
        // nâng cấp vẫn phải đọc được. Đây là quy tắc tương thích tiến
        // (forward compatibility): THÊM field thì an toàn, ĐỔI TÊN/XOÁ thì không.
        String json = """
                {"eventId":"%s","eventType":"StockReservationFailed","aggregateId":"%s",
                 "occurredAt":"2026-09-23T10:00:00Z","correlationId":"c",
                 "promotionCode":"SALE50",
                 "payload":{"orderId":"%s","reason":"INSUFFICIENT_STOCK",
                            "shortages":[{"productId":"%s","requested":2,"available":1}]}}
                """.formatted(UUID.randomUUID(), orderId, orderId, productId);

        EventEnvelope<StockReservationFailedEvent> event =
                mapper.readValue(json, new TypeReference<>() {});

        assertThat(event.payload().reason())
                .isEqualTo(StockReservationFailedEvent.Reason.INSUFFICIENT_STOCK);
        assertThat(event.payload().shortages()).singleElement()
                .satisfies(s -> assertThat(s.available()).isEqualTo(1));
    }

    @Test
    @DisplayName("Số tiền cần thu cũng đi dạng chuỗi, round-trip giữ nguyên scale")
    void paymentAmountIsStringAndRoundTrips() throws Exception {
        var payload = new PaymentRequestedEvent(orderId, UUID.randomUUID(), new BigDecimal("115500.5000"), "VND");
        var original = EventEnvelope.of(PaymentRequestedEvent.TYPE, orderId.toString(), "c", payload);

        String json = mapper.writeValueAsString(original);
        assertThat(mapper.readTree(json).at("/payload/amount").isTextual()).isTrue();

        EventEnvelope<PaymentRequestedEvent> restored = mapper.readValue(json, new TypeReference<>() {});
        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("Mỗi lần of() là một event mới với eventId riêng")
    void eachEnvelopeHasUniqueEventId() {
        assertThat(sampleOrderCreated().eventId()).isNotEqualTo(sampleOrderCreated().eventId());
    }
}
