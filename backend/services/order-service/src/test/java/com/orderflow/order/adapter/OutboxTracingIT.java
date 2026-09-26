package com.orderflow.order.adapter;

import com.orderflow.order.application.dto.PlaceOrderCommand;
import com.orderflow.order.application.port.in.PlaceOrderUseCase;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TUẦN 8 — trace của request đi XUYÊN qua bảng outbox tới Kafka.
 *
 * <p>{@code @AutoConfigureObservability}: {@code @SpringBootTest} mặc định tắt
 * tracing; bật lại cho riêng class này.
 */
@AutoConfigureObservability
class OutboxTracingIT extends AbstractOrderIT {

    @Autowired Tracer tracer;
    @Autowired PlaceOrderUseCase placeOrder;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("traceId của request được lưu vào outbox và xuất hiện trong header traceparent của message Kafka")
    void traceSurvivesTheOutbox() {
        Span request = tracer.nextSpan().name("POST /api/orders (giả lập)").start();
        String traceId = request.context().traceId();
        UUID orderId;
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            orderId = placeOrder.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), "VND", List.of(
                    new PlaceOrderCommand.Item(UUID.randomUUID(), "Kẹo dừa", 1, new BigDecimal("45000"))))).id();
        } finally {
            request.end();
        }

        String stored = jdbc.queryForObject(
                "SELECT trace_parent FROM outbox WHERE aggregate_id = ? AND topic = 'order.created'",
                String.class, orderId.toString());
        assertThat(stored).as("outbox lưu traceparent của request").contains(traceId);

        try (var consumer = orderCreatedConsumer()) {
            var record = awaitRecordWithKey(consumer, orderId.toString(), Duration.ofSeconds(20));
            var header = record.headers().lastHeader("traceparent");
            assertThat(header).as("message Kafka mang header traceparent").isNotNull();
            String sent = new String(header.value(), StandardCharsets.UTF_8);
            // Chỉ kiểm traceId, không kiểm spanId: trong test, context của class
            // khác (tracing TẮT) cũng poll chung bảng outbox và có thể gửi dòng này
            // trước. Poller nào gửi thì trace cũng phải không đứt — đó là thứ cần chứng minh.
            assertThat(sent)
                    .as("CÙNG trace với request — poller gửi trên thread khác, nửa giây sau, mà trace không đứt")
                    .contains(traceId);
        }
    }
}
