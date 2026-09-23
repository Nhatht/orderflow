package com.orderflow.order.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.order.OrderCreatedEvent;
import com.orderflow.order.application.dto.OrderView;
import com.orderflow.order.application.dto.PlaceOrderCommand;
import com.orderflow.order.application.port.in.GetOrderQuery;
import com.orderflow.order.application.port.in.PlaceOrderUseCase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * TIÊU CHÍ NGHIỆM THU TUẦN 4 — phía producer.
 *
 * <p>Chạy trên PostgreSQL và Kafka THẬT (cùng image với docker-compose).
 * Test đọc message bằng một {@link KafkaConsumer} thô, KHÔNG dùng lại cấu
 * hình Spring của service — để kiểm chứng đúng thứ mà một consumer bên ngoài
 * (inventory, hay service viết bằng ngôn ngữ khác) thật sự nhìn thấy trên dây.
 */
@Testcontainers
@SpringBootTest
class OrderCreatedPublishingIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_db")
            .withUsername("orderflow")
            .withPassword("orderflow");

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired PlaceOrderUseCase placeOrder;
    @Autowired GetOrderQuery getOrder;
    @Autowired ObjectMapper objectMapper;

    private static final UUID KEO_DUA = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private PlaceOrderCommand twoKeoDua() {
        return new PlaceOrderCommand(UUID.randomUUID(), "VND", List.of(
                new PlaceOrderCommand.Item(KEO_DUA, "Kẹo dừa Bến Tre", 2, new BigDecimal("45000"))));
    }

    @Test
    @DisplayName("Đặt đơn → order.created xuất hiện trên Kafka với key = orderId và payload đầy đủ")
    void placingOrderPublishesOrderCreated() throws Exception {
        try (var consumer = rawConsumer()) {
            OrderView order = placeOrder.placeOrder(twoKeoDua());

            ConsumerRecord<String, String> record = awaitRecordWithKey(consumer, order.id().toString());

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
        try (var consumer = rawConsumer()) {
            OrderView order = placeOrder.placeOrder(twoKeoDua());

            ConsumerRecord<String, String> record = awaitRecordWithKey(consumer, order.id().toString());

            assertThat(record.headers().lastHeader("__TypeId__")).isNull();
        }
    }

    @Test
    @DisplayName("Khi event tới nơi, đơn ĐÃ nằm trong database — phát sau commit, không phải trước")
    void eventIsPublishedOnlyAfterCommit() {
        try (var consumer = rawConsumer()) {
            OrderView order = placeOrder.placeOrder(twoKeoDua());

            awaitRecordWithKey(consumer, order.id().toString());

            // Nếu phát event BÊN TRONG transaction, consumer nhanh tay có thể
            // nhận event trước khi commit — và tra DB sẽ không thấy đơn.
            assertThat(getOrder.getById(order.id()).status()).isEqualTo("PENDING");
        }
    }

    // ---- helper ------------------------------------------------------------

    private KafkaConsumer<String, String> rawConsumer() {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(Topics.ORDER_CREATED));
        return consumer;
    }

    /**
     * Lọc theo key thay vì lấy "record đầu tiên": các test trong class dùng
     * chung một topic, và đọc từ earliest sẽ thấy cả message của test khác.
     */
    private ConsumerRecord<String, String> awaitRecordWithKey(KafkaConsumer<String, String> consumer,
                                                              String key) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            for (var record : consumer.poll(Duration.ofMillis(500))) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        return fail("No record with key %s on %s within 20s", key, Topics.ORDER_CREATED);
    }
}
