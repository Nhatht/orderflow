package com.orderflow.order.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.inventory.StockReleasedEvent;
import com.orderflow.contracts.inventory.StockReservationFailedEvent;
import com.orderflow.contracts.inventory.StockReservedEvent;
import com.orderflow.contracts.order.OrderCancelledEvent;
import com.orderflow.contracts.payment.PaymentCompletedEvent;
import com.orderflow.contracts.payment.PaymentFailedEvent;
import com.orderflow.contracts.payment.PaymentRequestedEvent;
import com.orderflow.order.application.dto.OrderView;
import com.orderflow.order.application.dto.PlaceOrderCommand;
import com.orderflow.order.application.dto.SagaReply;
import com.orderflow.order.application.dto.SagaView;
import com.orderflow.order.application.port.in.GetOrderQuery;
import com.orderflow.order.application.port.in.GetSagaQuery;
import com.orderflow.order.application.port.in.OrderSagaUseCase;
import com.orderflow.order.application.port.in.PlaceOrderUseCase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

/**
 * TIÊU CHÍ NGHIỆM THU TUẦN 6 — saga orchestrator.
 *
 * <p>Test đóng vai inventory và payment: gửi phản hồi thật lên Kafka, rồi quan
 * sát từ bên ngoài — lệnh saga phát ra, trạng thái đơn, trạng thái saga và
 * nhật ký từng bước.
 */
class OrderSagaIT extends AbstractOrderIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired PlaceOrderUseCase placeOrder;
    @Autowired GetOrderQuery getOrder;
    @Autowired GetSagaQuery getSaga;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrderSagaUseCase sagaUseCase;
    @Autowired JdbcTemplate jdbc;

    private KafkaConsumer<String, String> commands;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    @BeforeEach
    void subscribeToSagaCommands() {
        commands = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        commands.subscribe(List.of(Topics.PAYMENT_REQUESTED, Topics.ORDER_CONFIRMED, Topics.ORDER_CANCELLED));
    }

    @AfterEach
    void close() {
        commands.close();
    }

    // =========================================================================

    @Test
    @DisplayName("Luồng thành công: giữ hàng → thu đúng số tiền → CONFIRMED, nhật ký đủ 5 bước")
    void happyPathConfirmsOrder() throws Exception {
        OrderView order = placeOrder();
        UUID id = order.id();

        reply(Topics.STOCK_RESERVED, StockReservedEvent.TYPE, id,
                new StockReservedEvent(id, List.of(), Instant.now().plusSeconds(180)), "corr-happy");

        var request = awaitCommand(Topics.PAYMENT_REQUESTED, id);
        EventEnvelope<PaymentRequestedEvent> payment = objectMapper.readValue(request.value(), new TypeReference<>() {});
        assertThat(payment.payload().amount()).as("thu đúng tổng tiền đơn").isEqualByComparingTo(order.totalAmount());
        assertThat(payment.correlationId()).as("mang tiếp correlationId của phản hồi").isEqualTo("corr-happy");
        awaitSaga(id, "AWAITING_PAYMENT");
        assertThat(getOrder.getById(id).status()).isEqualTo("STOCK_RESERVED");

        reply(Topics.PAYMENT_COMPLETED, PaymentCompletedEvent.TYPE, id,
                new PaymentCompletedEvent(id, UUID.randomUUID(), order.totalAmount(), "VND"), "corr-happy");

        awaitCommand(Topics.ORDER_CONFIRMED, id);
        SagaView saga = awaitSaga(id, "COMPLETED");
        assertThat(getOrder.getById(id).status()).isEqualTo("CONFIRMED");
        assertThat(saga.steps()).extracting(s -> s.step() + ":" + s.outcome()).containsExactly(
                "RESERVE_STOCK:REQUESTED",
                "RESERVE_STOCK:SUCCEEDED",
                "PROCESS_PAYMENT:REQUESTED",
                "PROCESS_PAYMENT:SUCCEEDED",
                "CONFIRM_ORDER:SUCCEEDED");
    }

    @Test
    @DisplayName("Thanh toán bị từ chối → huỷ đơn, phát lệnh nhả hàng → stock.released → COMPENSATED")
    void paymentDeclinedTriggersCompensation() throws Exception {
        UUID id = placeOrder().id();

        reply(Topics.STOCK_RESERVED, StockReservedEvent.TYPE, id,
                new StockReservedEvent(id, List.of(), Instant.now().plusSeconds(180)), "c");
        awaitSaga(id, "AWAITING_PAYMENT");

        reply(Topics.PAYMENT_FAILED, PaymentFailedEvent.TYPE, id,
                new PaymentFailedEvent(id, UUID.randomUUID(), "CARD_DECLINED"), "c");

        var cancel = awaitCommand(Topics.ORDER_CANCELLED, id);
        EventEnvelope<OrderCancelledEvent> cancelled = objectMapper.readValue(cancel.value(), new TypeReference<>() {});
        assertThat(cancelled.payload().reason()).isEqualTo(OrderCancelledEvent.Reason.PAYMENT_DECLINED);
        awaitSaga(id, "COMPENSATING");
        assertThat(getOrder.getById(id).status()).isEqualTo("CANCELLED");

        reply(Topics.STOCK_RELEASED, StockReleasedEvent.TYPE, id, new StockReleasedEvent(id, List.of()), "c");

        SagaView saga = awaitSaga(id, "COMPENSATED");
        assertThat(saga.failureReason()).isEqualTo("CARD_DECLINED");
        assertThat(saga.steps()).extracting(s -> s.step() + ":" + s.outcome())
                .endsWith("PROCESS_PAYMENT:FAILED", "RELEASE_STOCK:REQUESTED", "RELEASE_STOCK:SUCCEEDED");
    }

    @Test
    @DisplayName("Hết hàng → huỷ đơn ngay, saga FAILED, không bao giờ xin thu tiền")
    void stockUnavailableCancelsWithoutPayment() throws Exception {
        UUID id = placeOrder().id();

        reply(Topics.STOCK_RESERVATION_FAILED, StockReservationFailedEvent.TYPE, id,
                new StockReservationFailedEvent(id, StockReservationFailedEvent.Reason.INSUFFICIENT_STOCK, List.of()), "c");

        var cancel = awaitCommand(Topics.ORDER_CANCELLED, id);
        EventEnvelope<OrderCancelledEvent> cancelled = objectMapper.readValue(cancel.value(), new TypeReference<>() {});
        assertThat(cancelled.payload().reason()).isEqualTo(OrderCancelledEvent.Reason.STOCK_UNAVAILABLE);
        assertThat(awaitSaga(id, "FAILED").failureReason()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(getOrder.getById(id).status()).isEqualTo("CANCELLED");
        assertThat(countSeen(Topics.PAYMENT_REQUESTED, id)).isZero();
    }

    @Test
    @DisplayName("Cùng một phản hồi đến hai lần → chỉ xin thu tiền MỘT lần")
    void duplicateReplyIsHandledOnce() throws Exception {
        UUID id = placeOrder().id();
        var envelope = EventEnvelope.of(StockReservedEvent.TYPE, id.toString(), "c",
                new StockReservedEvent(id, List.of(), Instant.now().plusSeconds(180)));

        send(Topics.STOCK_RESERVED, id.toString(), envelope);
        send(Topics.STOCK_RESERVED, id.toString(), envelope);   // at-least-once
        // Nói thật về test này (review tuần 6): bản trùng bị chặn bởi CẢ HAI lớp —
        // sổ processed_events VÀ máy trạng thái saga (lần hai thấy saga đã rời
        // STARTED). Xoá sổ đi test vẫn xanh. Với saga, máy trạng thái là lớp
        // bảo vệ chính; sổ là lớp phòng thủ thứ hai. Test này chứng minh KẾT QUẢ
        // (không xin thu tiền hai lần), không chứng minh riêng cái sổ.

        // Lính canh cùng key → cùng partition → xử lý sau hai bản kia.
        UUID sentinel = placeOrder().id();
        send(Topics.STOCK_RESERVED, id.toString(), EventEnvelope.of(StockReservedEvent.TYPE, sentinel.toString(), "c",
                new StockReservedEvent(sentinel, List.of(), Instant.now().plusSeconds(180))));
        awaitCommand(Topics.PAYMENT_REQUESTED, sentinel);

        assertThat(countSeen(Topics.PAYMENT_REQUESTED, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("Phản hồi đến muộn cho đơn đã huỷ → bị bỏ qua, KHÔNG làm kẹt consumer")
    void lateReplyIsIgnoredWithoutBlocking() throws Exception {
        UUID cancelledOrder = placeOrder().id();
        reply(Topics.STOCK_RESERVATION_FAILED, StockReservationFailedEvent.TYPE, cancelledOrder,
                new StockReservationFailedEvent(cancelledOrder,
                        StockReservationFailedEvent.Reason.INSUFFICIENT_STOCK, List.of()), "c");
        awaitSaga(cancelledOrder, "FAILED");

        // Late response: tiền báo đã thu cho một đơn đã huỷ.
        var late = EventEnvelope.of(PaymentCompletedEvent.TYPE, cancelledOrder.toString(), "c",
                new PaymentCompletedEvent(cancelledOrder, UUID.randomUUID(), new BigDecimal("90000"), "VND"));
        send(Topics.PAYMENT_COMPLETED, cancelledOrder.toString(), late);

        // Một đơn hợp lệ, phản hồi CÙNG KEY → cùng partition → đứng ngay sau
        // phản hồi muộn. Nếu consumer ném exception và thử lại mãi, đơn này
        // không bao giờ được xử lý.
        UUID healthy = placeOrder().id();
        reply(Topics.STOCK_RESERVED, StockReservedEvent.TYPE, healthy,
                new StockReservedEvent(healthy, List.of(), Instant.now().plusSeconds(180)), "c");
        awaitSaga(healthy, "AWAITING_PAYMENT");
        send(Topics.PAYMENT_COMPLETED, cancelledOrder.toString(), EventEnvelope.of(PaymentCompletedEvent.TYPE,
                healthy.toString(), "c", new PaymentCompletedEvent(healthy, UUID.randomUUID(), new BigDecimal("90000"), "VND")));

        awaitSaga(healthy, "COMPLETED");
        assertThat(getSaga.getByOrderId(cancelledOrder).status()).as("đơn đã huỷ vẫn là huỷ").isEqualTo("FAILED");
        assertThat(getOrder.getById(cancelledOrder).status()).isEqualTo("CANCELLED");

        // Bằng chứng TRỰC TIẾP rằng phản hồi muộn được xử lý mà KHÔNG ném lỗi:
        // dòng sổ chỉ tồn tại khi transaction đã commit. Nếu nhánh bỏ qua ném
        // exception, transaction rollback, dòng này không có — kể cả khi
        // message cuối cùng rơi vào DLT và đơn phía sau vẫn chạy được.
        // (Review tuần 6: trước khi có dòng này, cài lỗi "ném exception" vào
        // nhánh bỏ qua mà test vẫn xanh.)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, late.eventId()))
                .as("phản hồi muộn đã được ghi sổ — tức đã xử lý xong, không ném lỗi")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Phản hồi lệch trạng thái gọi thẳng vào orchestrator → không bao giờ ném lỗi")
    void outOfStateRepliesNeverThrow() {
        UUID failed = placeOrder().id();
        sagaUseCase.onStockReservationFailed(new SagaReply(UUID.randomUUID(), "c", failed, "INSUFFICIENT_STOCK"));

        // Đã huỷ mà tiền về / thanh toán báo lỗi / hàng báo đã nhả
        assertThatCode(() -> sagaUseCase.onPaymentCompleted(SagaReply.of(UUID.randomUUID(), "c", failed))).doesNotThrowAnyException();
        assertThatCode(() -> sagaUseCase.onPaymentFailed(SagaReply.of(UUID.randomUUID(), "c", failed))).doesNotThrowAnyException();
        assertThatCode(() -> sagaUseCase.onStockReleased(SagaReply.of(UUID.randomUUID(), "c", failed))).doesNotThrowAnyException();
        // Đơn không tồn tại
        assertThatCode(() -> sagaUseCase.onStockReserved(SagaReply.of(UUID.randomUUID(), "c", UUID.randomUUID()))).doesNotThrowAnyException();

        assertThat(getSaga.getByOrderId(failed).status()).isEqualTo("FAILED");
    }

    // ---- helper ------------------------------------------------------------

    private OrderView placeOrder() {
        return placeOrder.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), "VND", List.of(
                new PlaceOrderCommand.Item(UUID.randomUUID(), "Kẹo dừa", 2, new BigDecimal("45000")))));
    }

    private void reply(String topic, String type, UUID orderId, Object payload, String correlationId) throws Exception {
        send(topic, orderId.toString(), EventEnvelope.of(type, orderId.toString(), correlationId, payload));
    }

    private void send(String topic, String key, EventEnvelope<?> envelope) throws Exception {
        kafkaTemplate.send(topic, key, objectMapper.writeValueAsString(envelope)).get();
    }

    private SagaView awaitSaga(UUID orderId, String status) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        SagaView saga = getSaga.getByOrderId(orderId);
        while (!status.equals(saga.status()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
            saga = getSaga.getByOrderId(orderId);
        }
        assertThat(saga.status()).as("saga của đơn %s", orderId).isEqualTo(status);
        return saga;
    }

    private ConsumerRecord<String, String> awaitCommand(String topic, UUID orderId) {
        String key = orderId.toString();
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            var found = seen.stream().filter(r -> r.topic().equals(topic) && key.equals(r.key())).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            commands.poll(Duration.ofMillis(500)).forEach(seen::add);
        }
        return fail("No %s for order %s within %s", topic, orderId, TIMEOUT);
    }

    private long countSeen(String topic, UUID orderId) {
        commands.poll(Duration.ofSeconds(1)).forEach(seen::add);
        return seen.stream().filter(r -> r.topic().equals(topic) && orderId.toString().equals(r.key())).count();
    }
}
