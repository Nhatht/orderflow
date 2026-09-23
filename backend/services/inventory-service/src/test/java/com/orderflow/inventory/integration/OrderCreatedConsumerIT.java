package com.orderflow.inventory.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.inventory.StockReservationFailedEvent;
import com.orderflow.contracts.inventory.StockReservedEvent;
import com.orderflow.contracts.order.OrderCreatedEvent;
import com.orderflow.inventory.application.dto.StockView;
import com.orderflow.inventory.application.port.in.GetStockQuery;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * TIÊU CHÍ NGHIỆM THU TUẦN 4 — hai service nói chuyện qua Kafka.
 *
 * <p>Test đóng vai order-service: gửi {@code order.created} lên Kafka thật,
 * rồi quan sát từ bên ngoài — tồn kho trong database và event kết quả trên
 * {@code stock.reserved} / {@code stock.reservation-failed}. Không gọi thẳng
 * use case nào: nếu listener, converter hay cấu hình topic sai, test fail.
 */
class OrderCreatedConsumerIT extends AbstractInventoryIT {

    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired GetStockQuery getStock;
    @Autowired ObjectMapper objectMapper;

    private KafkaConsumer<String, String> resultConsumer;

    @BeforeEach
    void subscribeToResultTopics() {
        resultConsumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        resultConsumer.subscribe(List.of(Topics.STOCK_RESERVED, Topics.STOCK_RESERVATION_FAILED));
    }

    @AfterEach
    void closeConsumer() {
        resultConsumer.close();
    }

    // =========================================================================

    @Test
    @DisplayName("order.created → tồn kho tự giảm → stock.reserved mang tiếp correlationId")
    void reservesStockAndPublishesStockReserved() throws Exception {
        UUID keoDua = givenProductWithStock(10);
        UUID banhTrang = givenProductWithStock(5);
        var envelope = orderCreated(line(keoDua, 2), line(banhTrang, 1));

        send(envelope);

        var record = awaitRecord(Topics.STOCK_RESERVED, envelope.aggregateId());
        EventEnvelope<StockReservedEvent> reserved = objectMapper.readValue(record.value(), new TypeReference<>() {});

        assertThat(reserved.correlationId())
                .as("event phản hồi phải MANG TIẾP correlationId, không tạo mới")
                .isEqualTo(envelope.correlationId());
        assertThat(reserved.eventId()).isNotEqualTo(envelope.eventId());
        assertThat(reserved.payload().items()).hasSize(2);

        assertStock(keoDua, 8, 2);
        assertStock(banhTrang, 4, 1);
    }

    @Test
    @DisplayName("Một dòng thiếu hàng → KHÔNG dòng nào bị giữ (tất cả hoặc không)")
    void allOrNothingWhenOneLineIsShort() throws Exception {
        UUID plenty = givenProductWithStock(10);
        UUID scarce = givenProductWithStock(1);
        var envelope = orderCreated(line(plenty, 2), line(scarce, 3));

        send(envelope);

        var record = awaitRecord(Topics.STOCK_RESERVATION_FAILED, envelope.aggregateId());
        EventEnvelope<StockReservationFailedEvent> failed =
                objectMapper.readValue(record.value(), new TypeReference<>() {});

        assertThat(failed.payload().reason()).isEqualTo(StockReservationFailedEvent.Reason.INSUFFICIENT_STOCK);
        assertThat(failed.payload().shortages()).singleElement().satisfies(s -> {
            assertThat(s.productId()).isEqualTo(scarce);
            assertThat(s.requested()).isEqualTo(3);
            assertThat(s.available()).isEqualTo(1);
        });

        // Event kết quả chỉ được phát SAU commit — nên lúc này DB đã ở trạng thái cuối.
        assertStock(plenty, 10, 0);
        assertStock(scarce, 1, 0);
    }

    @Test
    @DisplayName("Cùng một event đến hai lần → chỉ giữ hàng MỘT lần, chỉ phát MỘT kết quả")
    void duplicateDeliveryIsProcessedOnce() throws Exception {
        UUID product = givenProductWithStock(10);
        var envelope = orderCreated(line(product, 2));

        send(envelope);
        awaitRecord(Topics.STOCK_RESERVED, envelope.aggregateId());

        send(envelope);   // at-least-once: y hệt, cùng eventId

        // Làm sao biết bản trùng ĐÃ được xử lý, không phải đang trên đường?
        // Không dùng sleep. Gửi một "event lính canh" CÙNG KEY với bản trùng:
        // cùng key → cùng partition → Kafka giao theo đúng thứ tự, và một
        // partition chỉ do một luồng consumer đọc tuần tự. Khi kết quả của
        // lính canh xuất hiện, bản trùng chắc chắn đã đi qua listener.
        UUID sentinelProduct = givenProductWithStock(10);
        var sentinel = orderCreated(line(sentinelProduct, 1));
        kafkaTemplate.send(Topics.ORDER_CREATED, envelope.aggregateId(), sentinel).get();
        awaitRecord(Topics.STOCK_RESERVED, sentinel.aggregateId());

        assertStock(product, 8, 2);
        assertThat(recordsSeenWithKey(Topics.STOCK_RESERVED, envelope.aggregateId()))
                .as("order-service không được nhận hai lần stock.reserved cho cùng một đơn")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Sản phẩm không tồn tại → reservation-failed với lý do UNKNOWN_PRODUCT")
    void unknownProductIsRejected() throws Exception {
        UUID ghost = UUID.randomUUID();
        var envelope = orderCreated(line(ghost, 1));

        send(envelope);

        var record = awaitRecord(Topics.STOCK_RESERVATION_FAILED, envelope.aggregateId());
        EventEnvelope<StockReservationFailedEvent> failed =
                objectMapper.readValue(record.value(), new TypeReference<>() {});
        assertThat(failed.payload().reason()).isEqualTo(StockReservationFailedEvent.Reason.UNKNOWN_PRODUCT);
    }

    @Test
    @DisplayName("Hai dòng cùng sản phẩm được gộp — không vi phạm UNIQUE(order_id, product_id)")
    void linesOfSameProductAreMerged() throws Exception {
        UUID product = givenProductWithStock(10);
        var envelope = orderCreated(line(product, 1), line(product, 2));

        send(envelope);

        awaitRecord(Topics.STOCK_RESERVED, envelope.aggregateId());
        assertStock(product, 7, 3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM stock_reservations WHERE order_id = ?", Integer.class,
                UUID.fromString(envelope.aggregateId())))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Message JSON hỏng không làm kẹt partition — message sau nó vẫn được xử lý")
    void poisonPillDoesNotBlockPartition() throws Exception {
        UUID product = givenProductWithStock(10);
        var good = orderCreated(line(product, 1));

        // Gửi rác bằng producer thô, CÙNG KEY với message tốt → cùng partition,
        // rác đứng TRƯỚC. Nếu consumer thử lại rác mãi, message tốt không bao
        // giờ tới lượt.
        try (var raw = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            raw.send(new ProducerRecord<>(Topics.ORDER_CREATED, good.aggregateId(), "{ this is not json")).get();
        }
        send(good);

        awaitRecord(Topics.STOCK_RESERVED, good.aggregateId());
        assertStock(product, 9, 1);
    }

    // ---- helper ------------------------------------------------------------

    private record Line(UUID productId, int quantity) {}

    private static Line line(UUID productId, int quantity) {
        return new Line(productId, quantity);
    }

    private static EventEnvelope<OrderCreatedEvent> orderCreated(Line... lines) {
        UUID orderId = UUID.randomUUID();
        var items = java.util.Arrays.stream(lines)
                .map(l -> new OrderCreatedEvent.Item(l.productId(), "Test product", l.quantity(), new BigDecimal("10000")))
                .toList();
        var payload = new OrderCreatedEvent(orderId, UUID.randomUUID(), "VND", new BigDecimal("10000"), items);
        return EventEnvelope.of(OrderCreatedEvent.TYPE, orderId.toString(), "corr-" + orderId, payload);
    }

    private void send(EventEnvelope<OrderCreatedEvent> envelope) throws Exception {
        kafkaTemplate.send(Topics.ORDER_CREATED, envelope.aggregateId(), envelope).get();
    }

    private void assertStock(UUID productId, int available, int reserved) {
        StockView stock = getStock.getByProductId(productId);
        assertThat(stock.availableQty()).as("available of %s", productId).isEqualTo(available);
        assertThat(stock.reservedQty()).as("reserved of %s", productId).isEqualTo(reserved);
    }

    /** Mọi record đã đọc được, giữ lại để đếm trùng. */
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            var found = seen.stream()
                    .filter(r -> r.topic().equals(topic) && key.equals(r.key()))
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            resultConsumer.poll(Duration.ofMillis(500)).forEach(seen::add);
        }
        return fail("No record on %s with key %s within 30s", topic, key);
    }

    private long recordsSeenWithKey(String topic, String key) {
        resultConsumer.poll(Duration.ofSeconds(1)).forEach(seen::add);
        return seen.stream().filter(r -> r.topic().equals(topic) && key.equals(r.key())).count();
    }
}
