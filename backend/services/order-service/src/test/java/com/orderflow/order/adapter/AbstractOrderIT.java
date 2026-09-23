package com.orderflow.order.adapter;

import com.orderflow.contracts.Topics;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.fail;

/**
 * Nền chung cho integration test của order-service: PostgreSQL + Kafka THẬT,
 * khởi động một lần cho mọi class con (singleton container pattern — xem
 * {@code AbstractInventoryIT} bên inventory để biết vì sao).
 *
 * <p>Test đọc message bằng {@link KafkaConsumer} thô, KHÔNG dùng lại cấu hình
 * Spring của service — để kiểm chứng đúng thứ mà một consumer bên ngoài
 * (inventory, hay service viết bằng ngôn ngữ khác) thật sự nhìn thấy trên dây.
 */
@SpringBootTest
public abstract class AbstractOrderIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_db")
            .withUsername("orderflow")
            .withPassword("orderflow");

    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    static {
        POSTGRES.start();
        KAFKA.start();
        createTopicsOwnedByOtherServices();
    }

    /**
     * Năm topic phản hồi mà order-service NGHE nhưng inventory/payment SỞ HỮU.
     * Ở đây không có hai service đó nên test tạo thay, cùng số partition.
     */
    private static void createTopicsOwnedByOtherServices() {
        try (var admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(Stream.of(
                            Topics.STOCK_RESERVED, Topics.STOCK_RESERVATION_FAILED, Topics.STOCK_RELEASED,
                            Topics.PAYMENT_COMPLETED, Topics.PAYMENT_FAILED)
                    .map(t -> new NewTopic(t, 3, (short) 1))
                    .toList()).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create reply topics", e);
        }
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    protected KafkaConsumer<String, String> orderCreatedConsumer() {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(Topics.ORDER_CREATED));
        return consumer;
    }

    /**
     * Lọc theo key thay vì lấy "record đầu tiên": mọi test dùng chung một
     * topic, và đọc từ earliest sẽ thấy cả message của test khác.
     */
    protected ConsumerRecord<String, String> awaitRecordWithKey(KafkaConsumer<String, String> consumer,
                                                                String key, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (var record : consumer.poll(Duration.ofMillis(500))) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        return fail("No record with key %s on %s within %s", key, Topics.ORDER_CREATED, timeout);
    }
}
