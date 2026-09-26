package com.orderflow.order.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.inventory.StockReleasedEvent;
import com.orderflow.contracts.inventory.StockReservedEvent;
import com.orderflow.contracts.order.OrderCancelledEvent;
import com.orderflow.contracts.payment.PaymentCompletedEvent;
import com.orderflow.order.application.dto.PlaceOrderCommand;
import com.orderflow.order.application.dto.SagaView;
import com.orderflow.order.application.port.in.GetOrderQuery;
import com.orderflow.order.application.port.in.GetSagaQuery;
import com.orderflow.order.application.port.in.PlaceOrderUseCase;
import com.orderflow.order.application.port.in.SagaTimeoutUseCase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phương án A — saga timeout (xem {@code docs/SAGA-TIMEOUT.md}).
 *
 * <p>Không chờ đồng hồ thật 3 phút: lùi {@code saga_state.updated_at} bằng SQL
 * rồi gọi thẳng use case, như {@code ReservationExpiryIT} làm bên inventory.
 */
class SagaTimeoutIT extends AbstractOrderIT {

    @Autowired PlaceOrderUseCase placeOrder;
    @Autowired GetOrderQuery getOrder;
    @Autowired GetSagaQuery getSaga;
    @Autowired SagaTimeoutUseCase sagaTimeout;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("Chờ tiền quá hạn → huỷ đơn, phát order.cancelled(PAYMENT_TIMEOUT); hàng nhả xong → COMPENSATED; tiền về muộn bị bỏ qua")
    void stalePaymentIsTimedOutAndCompensated() throws Exception {
        UUID id = awaitingPayment();
        backdate(id, Duration.ofMinutes(10));

        try (var cancelled = consumer(Topics.ORDER_CANCELLED)) {
            sagaTimeout.timeOutStalePayments(Instant.now());

            SagaView saga = awaitSaga(id, "COMPENSATING");
            assertThat(saga.failureReason()).isEqualTo("PAYMENT_TIMEOUT");
            assertThat(getOrder.getById(id).status()).isEqualTo("CANCELLED");

            EventEnvelope<OrderCancelledEvent> event = objectMapper.readValue(
                    awaitRecordWithKey(cancelled, id.toString(), Duration.ofSeconds(20)).value(),
                    new TypeReference<>() {});
            assertThat(event.payload().reason())
                    .as("inventory dựa vào lý do này để biết saga đang chờ stock.released")
                    .isEqualTo(OrderCancelledEvent.Reason.PAYMENT_TIMEOUT);
        }

        send(Topics.STOCK_RELEASED, id, StockReleasedEvent.TYPE, new StockReleasedEvent(id, List.of()));
        awaitSaga(id, "COMPENSATED");

        // Tiền về muộn sau khi đã huỷ: bỏ qua, log LATE PAYMENT — không hồi sinh đơn.
        send(Topics.PAYMENT_COMPLETED, id, PaymentCompletedEvent.TYPE,
                new PaymentCompletedEvent(id, UUID.randomUUID(), new BigDecimal("45000"), "VND"));
        Thread.sleep(1500);
        assertThat(getSaga.getByOrderId(id).status()).isEqualTo("COMPENSATED");
        assertThat(getOrder.getById(id).status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Saga chờ chưa tới hạn → không bị đụng tới")
    void freshSagaIsUntouched() throws Exception {
        UUID id = awaitingPayment();
        backdate(id, Duration.ofMinutes(1));   // mới chờ 1 phút, hạn là 3

        sagaTimeout.timeOutStalePayments(Instant.now());

        assertThat(getSaga.getByOrderId(id).status()).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    @DisplayName("Kafka ngừng: saga chờ đã 10 phút nhưng lệnh thu tiền CHƯA lên Kafka → không huỷ (review tuần 8)")
    void unsentPaymentRequestIsNotTimedOut() throws Exception {
        UUID id = awaitingPayment();
        backdate(id, Duration.ofMinutes(10));
        // Lệnh thu tiền vẫn nằm trong outbox, chưa gửi — như lúc Kafka/poller ngừng.
        jdbc.update("UPDATE outbox SET published_at = NULL WHERE aggregate_id = ? AND topic = 'payment.requested'",
                id.toString());

        sagaTimeout.timeOutStalePayments(Instant.now());

        // Poller có thể gửi lại dòng đó ngay lúc này (published_at = bây giờ) —
        // đồng hồ bắt đầu từ đó, nên dù thế nào cũng chưa được huỷ.
        assertThat(getSaga.getByOrderId(id).status())
                .as("payment chưa nhận lệnh thì chưa thể 'chờ tiền quá hạn'").isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    @DisplayName("Tiền về TRƯỚC khi job quét (dù đã quá hạn trên giấy) → đơn giữ CONFIRMED, bên tới trước thắng")
    void paymentThatArrivedFirstWins() throws Exception {
        UUID id = awaitingPayment();
        backdate(id, Duration.ofMinutes(10));

        send(Topics.PAYMENT_COMPLETED, id, PaymentCompletedEvent.TYPE,
                new PaymentCompletedEvent(id, UUID.randomUUID(), new BigDecimal("45000"), "VND"));
        awaitSaga(id, "COMPLETED");

        sagaTimeout.timeOutStalePayments(Instant.now());

        assertThat(getSaga.getByOrderId(id).status()).isEqualTo("COMPLETED");
        assertThat(getOrder.getById(id).status()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("Chạy job hai lần (hoặc hai instance) → đơn chỉ bị huỷ MỘT lần")
    void timeoutIsIdempotent() throws Exception {
        UUID id = awaitingPayment();
        backdate(id, Duration.ofMinutes(10));

        sagaTimeout.timeOutStalePayments(Instant.now());
        awaitSaga(id, "COMPENSATING");
        sagaTimeout.timeOutStalePayments(Instant.now());

        Integer failedPaymentSteps = jdbc.queryForObject("""
                SELECT count(*) FROM saga_step_log
                WHERE order_id = ? AND step = 'PROCESS_PAYMENT' AND outcome = 'FAILED'
                """, Integer.class, id);
        assertThat(failedPaymentSteps).isEqualTo(1);
    }

    // ---- helper ------------------------------------------------------------

    private UUID awaitingPayment() throws Exception {
        UUID id = placeOrder.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), "VND", List.of(
                new PlaceOrderCommand.Item(UUID.randomUUID(), "Kẹo dừa", 1, new BigDecimal("45000"))))).id();
        send(Topics.STOCK_RESERVED, id, StockReservedEvent.TYPE,
                new StockReservedEvent(id, List.of(), Instant.now().plusSeconds(1800)));
        awaitSaga(id, "AWAITING_PAYMENT");
        return id;
    }

    /** Giả như saga đã vào AWAITING_PAYMENT và lệnh thu tiền đã LÊN KAFKA từ {@code ago} trước. */
    private void backdate(UUID orderId, Duration ago) {
        Timestamp then = Timestamp.from(Instant.now().minus(ago));
        jdbc.update("UPDATE saga_state SET updated_at = ? WHERE order_id = ?", then, orderId);
        jdbc.update("UPDATE outbox SET published_at = ? WHERE aggregate_id = ? AND topic = 'payment.requested'",
                then, orderId.toString());
    }

    private KafkaConsumer<String, String> consumer(String topic) {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private void send(String topic, UUID orderId, String type, Object payload) throws Exception {
        var envelope = EventEnvelope.of(type, orderId.toString(), "c", payload);
        kafkaTemplate.send(topic, orderId.toString(), objectMapper.writeValueAsString(envelope)).get();
    }

    private SagaView awaitSaga(UUID orderId, String status) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        SagaView saga = getSaga.getByOrderId(orderId);
        while (!status.equals(saga.status()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
            saga = getSaga.getByOrderId(orderId);
        }
        assertThat(saga.status()).as("saga của đơn %s", orderId).isEqualTo(status);
        return saga;
    }
}
