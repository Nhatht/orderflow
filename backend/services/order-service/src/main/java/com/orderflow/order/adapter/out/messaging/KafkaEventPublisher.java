package com.orderflow.order.adapter.out.messaging;

import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.order.OrderCreatedEvent;
import com.orderflow.order.application.port.out.EventPublisherPort;
import com.orderflow.order.domain.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phát event thẳng lên Kafka — PHIÊN BẢN TUẦN 4, CÓ LỖ HỔNG CỐ Ý.
 *
 * <p><b>Lỗ hổng: dual write.</b> Đơn được commit vào PostgreSQL trước, rồi mới
 * gửi Kafka. Hai hệ thống, hai thao tác, không có transaction chung:
 * <pre>
 *   commit đơn vào DB  ✅
 *   ── app crash / Kafka sập / mạng đứt ở đây ──
 *   gửi order.created  ❌  → đơn nằm PENDING mãi, inventory không bao giờ biết
 * </pre>
 * Không exception nào bay về client, không log đỏ nào — chỉ có một đơn kẹt.
 *
 * <p>Tuần 5 thay class này bằng Transactional Outbox: ghi event vào bảng
 * {@code outbox} TRONG CÙNG transaction với đơn, rồi một poller đẩy lên Kafka.
 * Để nguyên lỗ hổng ở tuần 4 là có chủ đích — tuần 5 sẽ tái hiện nó (tắt
 * Kafka, đặt đơn, thấy đơn kẹt) trước khi sửa, để thấy Outbox giải quyết
 * vấn đề THẬT chứ không phải vấn đề trên giấy.
 *
 * <p><b>Kafka key = orderId.</b> Kafka chỉ bảo đảm thứ tự TRONG một partition.
 * Cùng key thì cùng partition, nên mọi event của một đơn đến consumer đúng
 * thứ tự phát ra.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventPublisher implements EventPublisherPort {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void publishOrderCreated(Order order) {
        var payload = new OrderCreatedEvent(
                order.id(),
                order.customerId(),
                order.currency(),
                order.totalAmount().amount(),
                order.items().stream()
                        .map(i -> new OrderCreatedEvent.Item(
                                i.productId(), i.productName(), i.quantity(), i.unitPrice().amount()))
                        .toList());

        // correlationId: đơn là điểm khởi đầu của luồng, nên sinh mới ở đây.
        // Từ tuần 7, API Gateway sinh correlationId ở rìa hệ thống và truyền
        // xuống qua header — lúc đó chỗ này đọc lại thay vì tự tạo.
        var envelope = EventEnvelope.of(
                OrderCreatedEvent.TYPE, order.id().toString(), UUID.randomUUID().toString(), payload);

        // send() là BẤT ĐỒNG BỘ: trả về ngay, kết quả đến sau qua callback.
        // Không chặn luồng HTTP chờ Kafka — nhưng cũng nghĩa là client đã nhận
        // 201 trước khi biết event có tới Kafka hay không. Thêm một lý do cho Outbox.
        kafkaTemplate.send(Topics.ORDER_CREATED, envelope.aggregateId(), envelope)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("LOST EVENT {} for order {} — order will stay PENDING until week-5 outbox exists",
                                envelope.eventId(), order.id(), ex);
                    } else {
                        var meta = result.getRecordMetadata();
                        log.info("Published {} eventId={} order={} -> {}-{}@{}",
                                envelope.eventType(), envelope.eventId(), order.id(),
                                meta.topic(), meta.partition(), meta.offset());
                    }
                });
    }
}
