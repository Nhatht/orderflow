package com.orderflow.order.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.order.OrderCreatedEvent;
import com.orderflow.order.adapter.out.messaging.OutboxPoller;
import com.orderflow.order.application.dto.OrderView;
import com.orderflow.order.application.dto.PlaceOrderCommand;
import com.orderflow.order.application.port.in.PlaceOrderUseCase;
import com.orderflow.order.application.port.out.EventPublisherPort;
import com.orderflow.order.domain.model.Money;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderItem;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TIÊU CHÍ NGHIỆM THU TUẦN 5 — Transactional Outbox.
 *
 * <p>Bốn khẳng định, mỗi cái một test:
 * <ol>
 *   <li>Không thể ghi outbox ngoài transaction (lưới an toàn MANDATORY).</li>
 *   <li>Rollback thì event cũng biến mất — đơn và event sống chết cùng nhau.</li>
 *   <li>Kafka sập: đặt đơn vẫn chạy, event chờ trong database, Kafka sống lại
 *       là tự gửi. <b>Đây là lỗi đã tái hiện thật ở cuối tuần 4.</b></li>
 *   <li>Nhiều poller chạy song song không gửi trùng (FOR UPDATE; SKIP LOCKED
 *       để chúng không phải chờ nhau).</li>
 * </ol>
 */
class OutboxIT extends AbstractOrderIT {

    @Autowired PlaceOrderUseCase placeOrder;
    @Autowired EventPublisherPort eventPublisher;
    @Autowired OutboxPoller poller;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private static Order someOrder() {
        return Order.place(UUID.randomUUID(), List.of(
                OrderItem.create(UUID.randomUUID(), "Test product", 1, Money.of("1000", "VND"))));
    }

    private static PlaceOrderCommand somePlaceOrderCommand() {
        return new PlaceOrderCommand(UUID.randomUUID(), "VND", List.of(
                new PlaceOrderCommand.Item(UUID.randomUUID(), "Test product", 1, new BigDecimal("1000"))));
    }

    private Integer outboxRows(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                Integer.class, orderId.toString());
    }

    private boolean isPublished(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT bool_and(published_at IS NOT NULL) FROM outbox WHERE aggregate_id = ?",
                Boolean.class, orderId.toString()));
    }

    // =========================================================================

    @Test
    @DisplayName("Ghi outbox ngoài transaction → exception ngay, không lặng lẽ quay về lỗi dual write")
    void publisherRefusesToRunOutsideTransaction() {
        assertThatThrownBy(() -> eventPublisher.publishOrderCreated(someOrder()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("Transaction rollback → dòng outbox cũng biến mất")
    void rollbackDiscardsTheEvent() {
        Order order = someOrder();

        tx.executeWithoutResult(status -> {
            eventPublisher.publishOrderCreated(order);
            status.setRollbackOnly();   // giả lập: lưu đơn thất bại sau khi đã ghi event
        });

        assertThat(outboxRows(order.id()))
                .as("không có đơn thì cũng không có event — không bao giờ bịa event cho đơn ma")
                .isZero();
    }

    @Test
    @DisplayName("Kafka sập: đặt đơn vẫn nhanh, event chờ trong DB, Kafka sống lại thì tự gửi đi")
    void eventSurvivesKafkaOutage() throws Exception {
        var docker = DockerClientFactory.instance().client();
        OrderView order;

        try (var consumer = orderCreatedConsumer()) {
            // pause (không phải stop): container đông cứng, kết nối TCP treo
            // chứ không bị từ chối — giống broker quá tải hoặc đứt mạng thật.
            docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
            try {
                Instant start = Instant.now();
                order = placeOrder.placeOrder(somePlaceOrderCommand());
                Duration took = Duration.between(start, Instant.now());

                assertThat(took)
                        .as("luồng HTTP không còn chạm tới Kafka — Kafka treo không làm đặt đơn treo theo")
                        .isLessThan(Duration.ofSeconds(2));

                // Suốt thời gian Kafka sập, event nằm yên trong outbox, chưa gửi.
                Instant until = Instant.now().plusSeconds(3);
                while (Instant.now().isBefore(until)) {
                    assertThat(outboxRows(order.id())).isEqualTo(1);
                    assertThat(isPublished(order.id())).isFalse();
                    Thread.sleep(500);
                }
            } finally {
                docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
            }

            ConsumerRecord<String, String> record =
                    awaitRecordWithKey(consumer, order.id().toString(), Duration.ofSeconds(60));

            EventEnvelope<OrderCreatedEvent> envelope =
                    objectMapper.readValue(record.value(), new TypeReference<>() {});
            UUID outboxId = jdbc.queryForObject(
                    "SELECT id FROM outbox WHERE aggregate_id = ?", UUID.class, order.id().toString());

            assertThat(envelope.eventId())
                    .as("eventId trên dây = id dòng outbox → gửi lại bao nhiêu lần cũng cùng một eventId")
                    .isEqualTo(outboxId);
        }

        Instant deadline = Instant.now().plusSeconds(15);
        while (!isPublished(order.id()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(isPublished(order.id())).as("dòng outbox được đánh dấu đã gửi").isTrue();
    }

    @Test
    @DisplayName("4 poller tranh nhau cùng lúc (+ poller nền) → mỗi event lên Kafka đúng MỘT lần")
    void concurrentPollersRelayEachRowOnce() throws Exception {
        int events = 40;
        List<Order> orders = IntStream.range(0, events).mapToObj(i -> someOrder()).toList();

        try (var consumer = orderCreatedConsumer()) {
            // Ghi cả 40 event trong một transaction → chúng xuất hiện cùng lúc,
            // và mọi poller cùng lao vào tranh.
            tx.executeWithoutResult(s -> orders.forEach(eventPublisher::publishOrderCreated));

            try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
                List<Future<?>> workers = IntStream.range(0, 4)
                        .mapToObj(i -> pool.submit(() -> {
                            while (poller.relayBatch() > 0) { /* xả tới khi hết */ }
                        }))
                        .collect(Collectors.toList());
                for (Future<?> w : workers) {
                    w.get(60, TimeUnit.SECONDS);
                }
            }

            Set<String> keys = orders.stream().map(o -> o.id().toString()).collect(Collectors.toSet());
            Map<String, Integer> copies = new HashMap<>();

            // Đọc tới khi thấy đủ 40, rồi đọc thêm 2 giây để bắt bản trùng đến muộn.
            Instant deadline = Instant.now().plusSeconds(30);
            Instant quietUntil = null;
            while (Instant.now().isBefore(deadline) && (quietUntil == null || Instant.now().isBefore(quietUntil))) {
                for (var r : consumer.poll(Duration.ofMillis(300))) {
                    if (keys.contains(r.key())) {
                        copies.merge(r.key(), 1, Integer::sum);
                    }
                }
                if (quietUntil == null && copies.size() == events) {
                    quietUntil = Instant.now().plusSeconds(2);
                }
            }

            assertThat(copies).as("mọi event đều lên Kafka").hasSize(events);
            // Bỏ "FOR UPDATE SKIP LOCKED" khỏi OutboxPoller rồi chạy lại: mỗi event
            // lên Kafka 4 lần (đã thử ngày 23/09/2026). Xem javadoc OutboxPoller.
            assertThat(copies.values())
                    .as("FOR UPDATE: không có event nào bị hai poller cùng gửi")
                    .containsOnly(1);
        }
    }
}
