package com.orderflow.order.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.inventory.StockReservationExpiredEvent;
import com.orderflow.contracts.inventory.StockReservedEvent;
import com.orderflow.contracts.payment.PaymentCompletedEvent;
import com.orderflow.order.application.dto.PlaceOrderCommand;
import com.orderflow.order.application.dto.SagaView;
import com.orderflow.order.application.port.in.GetOrderQuery;
import com.orderflow.order.application.port.in.GetSagaQuery;
import com.orderflow.order.application.port.in.PlaceOrderUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TUẦN 7 — saga phản ứng khi inventory tự thu hồi hàng vì phiếu giữ quá hạn.
 *
 * <p>Class riêng (không thêm vào {@code OrderSagaIT}) để không đụng file đang
 * được sửa song song ở nhánh chính.
 */
class ReservationExpiredSagaIT extends AbstractOrderIT {

    @Autowired PlaceOrderUseCase placeOrder;
    @Autowired GetOrderQuery getOrder;
    @Autowired GetSagaQuery getSaga;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("Phiếu hết hạn khi đang chờ thanh toán → huỷ đơn; tiền về sau đó bị bỏ qua (late response)")
    void expiryWhileAwaitingPaymentCancelsOrder() throws Exception {
        UUID id = placeOrder.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), "VND", List.of(
                new PlaceOrderCommand.Item(UUID.randomUUID(), "Kẹo dừa", 1, new BigDecimal("45000"))))).id();

        send(Topics.STOCK_RESERVED, id, StockReservedEvent.TYPE,
                new StockReservedEvent(id, List.of(), Instant.now().plusSeconds(180)));
        awaitSaga(id, "AWAITING_PAYMENT");

        send(Topics.STOCK_RESERVATION_EXPIRED, id, StockReservationExpiredEvent.TYPE,
                new StockReservationExpiredEvent(id, List.of()));

        SagaView saga = awaitSaga(id, "FAILED");
        assertThat(saga.failureReason()).isEqualTo("RESERVATION_EXPIRED");
        assertThat(getOrder.getById(id).status()).isEqualTo("CANCELLED");

        // Tiền về muộn: saga đã FAILED → bỏ qua, log REFUND REQUIRED, không hồi sinh đơn.
        send(Topics.PAYMENT_COMPLETED, id, PaymentCompletedEvent.TYPE,
                new PaymentCompletedEvent(id, UUID.randomUUID(), new BigDecimal("45000"), "VND"));
        Thread.sleep(1500);
        assertThat(getSaga.getByOrderId(id).status()).isEqualTo("FAILED");
        assertThat(getOrder.getById(id).status()).isEqualTo("CANCELLED");
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
