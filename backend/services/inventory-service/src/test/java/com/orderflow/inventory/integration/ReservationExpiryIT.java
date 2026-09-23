package com.orderflow.inventory.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.inventory.StockReservationExpiredEvent;
import com.orderflow.inventory.application.dto.ReservationOutcome;
import com.orderflow.inventory.application.dto.ReserveOrderStockCommand;
import com.orderflow.inventory.application.dto.StockView;
import com.orderflow.inventory.application.port.in.ExpireReservationsUseCase;
import com.orderflow.inventory.application.port.in.GetStockQuery;
import com.orderflow.inventory.application.port.in.ReserveOrderStockUseCase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * TUẦN 7 — job nhả phiếu giữ hàng quá hạn.
 *
 * <p>Không chờ đồng hồ thật 3 phút: test giữ hàng bình thường, rồi dời
 * {@code expires_at} về quá khứ bằng SQL và gọi thẳng use case.
 */
class ReservationExpiryIT extends AbstractInventoryIT {

    @Autowired ReserveOrderStockUseCase reserveOrderStock;
    @Autowired ExpireReservationsUseCase expireReservations;
    @Autowired GetStockQuery getStock;
    @Autowired ObjectMapper objectMapper;

    private KafkaConsumer<String, String> expiredEvents;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        expiredEvents = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        expiredEvents.subscribe(List.of(Topics.STOCK_RESERVATION_EXPIRED));
    }

    @AfterEach
    void close() {
        expiredEvents.close();
    }

    @Test
    @DisplayName("Phiếu quá hạn → hàng về kho, phiếu EXPIRED, phát stock.reservation-expired")
    void expiredReservationIsReleased() throws Exception {
        UUID product = givenProductWithStock(10);
        UUID orderId = reserve(product, 3);
        makeOverdue(orderId);

        expireReservations.expireDue(Instant.now());

        assertStock(product, 10, 0);
        assertThat(reservationStatus(orderId)).isEqualTo("EXPIRED");

        EventEnvelope<StockReservationExpiredEvent> event =
                objectMapper.readValue(awaitExpired(orderId).value(), new TypeReference<>() {});
        assertThat(event.payload().items()).singleElement().satisfies(i -> assertThat(i.quantity()).isEqualTo(3));
        assertThat(event.correlationId()).startsWith("expiry-");
    }

    @Test
    @DisplayName("Phiếu CHƯA quá hạn thì không bị đụng tới")
    void unexpiredReservationIsUntouched() {
        UUID product = givenProductWithStock(10);
        UUID orderId = reserve(product, 2);

        expireReservations.expireDue(Instant.now());

        assertStock(product, 8, 2);
        assertThat(reservationStatus(orderId)).isEqualTo("HELD");
    }

    @Test
    @DisplayName("4 instance cùng chạy job một lúc → hàng chỉ được trả về MỘT lần")
    void concurrentJobsReleaseOnce() throws Exception {
        UUID product = givenProductWithStock(10);
        UUID orderId = reserve(product, 3);
        makeOverdue(orderId);
        Instant now = Instant.now();

        int total = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> runs = IntStream.range(0, 4)
                    .mapToObj(i -> pool.submit(() -> expireReservations.expireDue(now)))
                    .toList();
            for (var run : runs) {
                total += run.get(30, TimeUnit.SECONDS);
            }
        }

        assertStock(product, 10, 0);   // không phải 13, 16 hay 19
        assertThat(total).as("tổng số phiếu được nhả qua mọi instance").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'StockReservationExpired'",
                Integer.class, orderId.toString())).isEqualTo(1);
    }

    // ---- helper ------------------------------------------------------------

    private UUID reserve(UUID product, int quantity) {
        UUID orderId = UUID.randomUUID();
        var outcome = reserveOrderStock.reserveForOrder(new ReserveOrderStockCommand(
                UUID.randomUUID(), "c", orderId, List.of(new ReserveOrderStockCommand.Item(product, quantity))));
        assertThat(outcome).isInstanceOf(ReservationOutcome.Reserved.class);
        return orderId;
    }

    /** Dời hạn chót về quá khứ — thay cho việc chờ 3 phút thật. */
    private void makeOverdue(UUID orderId) {
        jdbc.update("UPDATE stock_reservations SET expires_at = ? WHERE order_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), orderId);
    }

    private void assertStock(UUID product, int available, int reserved) {
        StockView stock = getStock.getByProductId(product);
        assertThat(stock.availableQty()).as("available").isEqualTo(available);
        assertThat(stock.reservedQty()).as("reserved").isEqualTo(reserved);
    }

    private String reservationStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM stock_reservations WHERE order_id = ?", String.class, orderId);
    }

    private ConsumerRecord<String, String> awaitExpired(UUID orderId) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            var found = seen.stream().filter(r -> orderId.toString().equals(r.key())).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            expiredEvents.poll(Duration.ofMillis(500)).forEach(seen::add);
        }
        return fail("No stock.reservation-expired for order %s within 30s", orderId);
    }
}
