package com.orderflow.inventory.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.inventory.StockReleasedEvent;
import com.orderflow.contracts.order.OrderCancelledEvent;
import com.orderflow.contracts.order.OrderConfirmedEvent;
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
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * TIÊU CHÍ NGHIỆM THU TUẦN 6 — phía inventory: chốt hàng và ĐỀN BÙ.
 *
 * <p>Giữ hàng bằng use case trực tiếp (đã được kiểm chứng qua Kafka ở
 * {@link OrderCreatedConsumerIT}), rồi gửi kết cục saga thật qua Kafka.
 */
class OrderOutcomeConsumerIT extends AbstractInventoryIT {

    @Autowired ReserveOrderStockUseCase reserveOrderStock;
    @Autowired GetStockQuery getStock;
    @Autowired ExpireReservationsUseCase expireReservations;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;

    private KafkaConsumer<String, String> released;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        released = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        released.subscribe(List.of(Topics.STOCK_RELEASED));
    }

    @AfterEach
    void close() {
        released.close();
    }

    // =========================================================================

    @Test
    @DisplayName("order.cancelled → hàng quay về kho (ĐỀN BÙ) → stock.released")
    void cancellationReleasesStock() throws Exception {
        UUID product = givenProductWithStock(10);
        UUID orderId = reserve(product, 3);
        assertStock(product, 7, 3);

        send(Topics.ORDER_CANCELLED, orderId, EventEnvelope.of(OrderCancelledEvent.TYPE, orderId.toString(), "c",
                new OrderCancelledEvent(orderId, UUID.randomUUID(), OrderCancelledEvent.Reason.PAYMENT_DECLINED)));

        var record = awaitReleased(orderId);
        EventEnvelope<StockReleasedEvent> event = objectMapper.readValue(record.value(), new TypeReference<>() {});
        assertThat(event.payload().items()).singleElement().satisfies(i -> {
            assertThat(i.productId()).isEqualTo(product);
            assertThat(i.quantity()).isEqualTo(3);
        });
        assertStock(product, 10, 0);
        assertThat(reservationStatus(orderId))
                .as("đền bù không xoá dấu vết — phiếu vẫn còn, chuyển sang RELEASED")
                .isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("order.confirmed → hàng rời kho thật: reserved về 0, tổng tồn giảm")
    void confirmationRemovesStockPermanently() throws Exception {
        UUID product = givenProductWithStock(10);
        UUID orderId = reserve(product, 4);

        send(Topics.ORDER_CONFIRMED, orderId,
                EventEnvelope.of(OrderConfirmedEvent.TYPE, orderId.toString(), "c", new OrderConfirmedEvent(orderId, UUID.randomUUID())));

        awaitReservationStatus(orderId, "CONFIRMED");
        StockView stock = getStock.getByProductId(product);
        assertThat(stock.availableQty()).isEqualTo(6);
        assertThat(stock.reservedQty()).isZero();
        assertThat(stock.totalQty()).as("lúc DUY NHẤT tổng tồn kho giảm").isEqualTo(6);
    }

    @Test
    @DisplayName("order.cancelled đến hai lần → hàng trả về MỘT lần, stock.released phát MỘT lần")
    void duplicateCancellationReleasesOnce() throws Exception {
        UUID product = givenProductWithStock(10);
        UUID orderId = reserve(product, 3);
        var cancel = EventEnvelope.of(OrderCancelledEvent.TYPE, orderId.toString(), "c",
                new OrderCancelledEvent(orderId, UUID.randomUUID(), OrderCancelledEvent.Reason.PAYMENT_DECLINED));

        send(Topics.ORDER_CANCELLED, orderId, cancel);
        awaitReleased(orderId);
        send(Topics.ORDER_CANCELLED, orderId, cancel);

        // Lính canh cùng key → cùng partition → xử lý sau bản trùng.
        UUID sentinelProduct = givenProductWithStock(5);
        UUID sentinel = reserve(sentinelProduct, 1);
        send(Topics.ORDER_CANCELLED, orderId, EventEnvelope.of(OrderCancelledEvent.TYPE, sentinel.toString(), "c",
                new OrderCancelledEvent(sentinel, UUID.randomUUID(), OrderCancelledEvent.Reason.PAYMENT_DECLINED)));
        awaitReleased(sentinel);

        assertStock(product, 10, 0);   // không phải 13
        assertThat(countReleased(orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Huỷ đơn chưa từng giữ hàng (hết hàng) → không làm gì, không phát gì, không lỗi")
    void cancellingOrderWithoutReservationIsNoOp() throws Exception {
        UUID neverReserved = UUID.randomUUID();
        send(Topics.ORDER_CANCELLED, neverReserved, EventEnvelope.of(OrderCancelledEvent.TYPE,
                neverReserved.toString(), "c",
                new OrderCancelledEvent(neverReserved, UUID.randomUUID(), OrderCancelledEvent.Reason.STOCK_UNAVAILABLE)));

        // Lính canh cùng key: nếu đơn kia làm kẹt consumer, lính canh không bao giờ tới.
        UUID product = givenProductWithStock(5);
        UUID sentinel = reserve(product, 1);
        send(Topics.ORDER_CANCELLED, neverReserved, EventEnvelope.of(OrderCancelledEvent.TYPE, sentinel.toString(), "c",
                new OrderCancelledEvent(sentinel, UUID.randomUUID(), OrderCancelledEvent.Reason.PAYMENT_DECLINED)));
        awaitReleased(sentinel);

        assertThat(countReleased(neverReserved)).isZero();
    }

    @Test
    @DisplayName("Huỷ vì thanh toán mà không còn gì để nhả → VẪN phát stock.released (rỗng) để saga khỏi treo")
    void paymentDeclinedAlwaysAcknowledged() throws Exception {
        // Review tuần 6: phiếu có thể đã được nhả trước (hết hạn, hoặc lần giao
        // trước). Nếu inventory im lặng, saga ở COMPENSATING chờ mãi.
        UUID orderId = UUID.randomUUID();   // chưa từng giữ hàng

        send(Topics.ORDER_CANCELLED, orderId, EventEnvelope.of(OrderCancelledEvent.TYPE, orderId.toString(), "c",
                new OrderCancelledEvent(orderId, UUID.randomUUID(), OrderCancelledEvent.Reason.PAYMENT_DECLINED)));

        var record = awaitReleased(orderId);
        EventEnvelope<StockReleasedEvent> event = objectMapper.readValue(record.value(), new TypeReference<>() {});
        assertThat(event.payload().items()).isEmpty();
    }

    @Test
    @DisplayName("Huỷ vì saga timeout → nhả hàng VÀ phát stock.released (saga đang chờ ở COMPENSATING)")
    void paymentTimeoutReleasesAndAcknowledges() throws Exception {
        UUID product = givenProductWithStock(10);
        UUID orderId = reserve(product, 3);

        send(Topics.ORDER_CANCELLED, orderId, EventEnvelope.of(OrderCancelledEvent.TYPE, orderId.toString(), "c",
                new OrderCancelledEvent(orderId, UUID.randomUUID(), OrderCancelledEvent.Reason.PAYMENT_TIMEOUT)));

        awaitReleased(orderId);
        assertStock(product, 10, 0);

        // Bẫy: không còn gì để nhả (phiếu đã bị job trả trước) mà inventory im
        // lặng thì saga kẹt COMPENSATING mãi. Phải vẫn phát, như PAYMENT_DECLINED.
        UUID nothingHeld = UUID.randomUUID();
        send(Topics.ORDER_CANCELLED, nothingHeld, EventEnvelope.of(OrderCancelledEvent.TYPE, nothingHeld.toString(), "c",
                new OrderCancelledEvent(nothingHeld, UUID.randomUUID(), OrderCancelledEvent.Reason.PAYMENT_TIMEOUT)));
        awaitReleased(nothingHeld);
    }

    @Test
    @DisplayName("Phương án D: order.confirmed tới SAU khi phiếu hết hạn, còn hàng → lấy lại hàng, phiếu CONFIRMED")
    void confirmationAfterExpiryReclaimsStock() throws Exception {
        UUID product = givenProductWithStock(10);
        UUID orderId = reserve(product, 4);
        expire(orderId);
        assertStock(product, 10, 0);   // job đã trả hàng về kệ

        send(Topics.ORDER_CONFIRMED, orderId, EventEnvelope.of(OrderConfirmedEvent.TYPE, orderId.toString(), "c",
                new OrderConfirmedEvent(orderId, UUID.randomUUID())));

        awaitReservationStatus(orderId, "CONFIRMED");
        assertStock(product, 6, 0);
    }

    @Test
    @DisplayName("Phương án D: order.confirmed tới SAU khi phiếu hết hạn, hàng đã bán mất → OVERSOLD, không đụng hàng người khác")
    void confirmationAfterExpiryWhenSoldOut() throws Exception {
        UUID product = givenProductWithStock(1);
        UUID late = reserve(product, 1);
        expire(late);
        UUID someoneElse = reserve(product, 1);   // món duy nhất đã về tay người khác
        assertStock(product, 0, 1);

        send(Topics.ORDER_CONFIRMED, late, EventEnvelope.of(OrderConfirmedEvent.TYPE, late.toString(), "c",
                new OrderConfirmedEvent(late, UUID.randomUUID())));

        // Lính canh cùng key → cùng partition → xử lý SAU đơn kia.
        UUID sentinel = reserve(givenProductWithStock(5), 1);
        send(Topics.ORDER_CONFIRMED, late, EventEnvelope.of(OrderConfirmedEvent.TYPE, sentinel.toString(), "c",
                new OrderConfirmedEvent(sentinel, UUID.randomUUID())));
        awaitReservationStatus(sentinel, "CONFIRMED");

        assertThat(reservationStatus(late)).as("không lấy được hàng → phiếu giữ nguyên EXPIRED").isEqualTo("EXPIRED");
        assertThat(reservationStatus(someoneElse)).isEqualTo("HELD");
        assertStock(product, 0, 1);
    }

    // ---- helper ------------------------------------------------------------

    /** Cho phiếu quá hạn rồi chạy job hết hạn một lần — như lúc job thắng cuộc đua. */
    private void expire(UUID orderId) {
        jdbc.update("UPDATE stock_reservations SET expires_at = ? WHERE order_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), orderId);
        expireReservations.expireDue(Instant.now());
        assertThat(reservationStatus(orderId)).isEqualTo("EXPIRED");
    }


    private UUID reserve(UUID product, int quantity) {
        UUID orderId = UUID.randomUUID();
        var outcome = reserveOrderStock.reserveForOrder(new ReserveOrderStockCommand(
                UUID.randomUUID(), "c", orderId, List.of(new ReserveOrderStockCommand.Item(product, quantity))));
        assertThat(outcome).isInstanceOf(ReservationOutcome.Reserved.class);
        return orderId;
    }

    private void send(String topic, UUID key, EventEnvelope<?> envelope) throws Exception {
        kafkaTemplate.send(topic, key.toString(), objectMapper.writeValueAsString(envelope)).get();
    }

    private void assertStock(UUID product, int available, int reserved) {
        StockView stock = getStock.getByProductId(product);
        assertThat(stock.availableQty()).as("available").isEqualTo(available);
        assertThat(stock.reservedQty()).as("reserved").isEqualTo(reserved);
    }

    private String reservationStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM stock_reservations WHERE order_id = ?", String.class, orderId);
    }

    private void awaitReservationStatus(UUID orderId, String status) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (!status.equals(reservationStatus(orderId)) && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(reservationStatus(orderId)).isEqualTo(status);
    }

    private ConsumerRecord<String, String> awaitReleased(UUID orderId) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            var found = seen.stream().filter(r -> orderId.toString().equals(r.key())).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            released.poll(Duration.ofMillis(500)).forEach(seen::add);
        }
        return fail("No stock.released for order %s within 30s", orderId);
    }

    private long countReleased(UUID orderId) {
        released.poll(Duration.ofSeconds(1)).forEach(seen::add);
        return seen.stream().filter(r -> orderId.toString().equals(r.key())).count();
    }
}
