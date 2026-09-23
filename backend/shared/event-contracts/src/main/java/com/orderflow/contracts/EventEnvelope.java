package com.orderflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phong bì chung bọc MỌI event đi qua Kafka.
 *
 * <p>Tách "phong bì" khỏi "nội dung" để mọi consumer xử lý được phần siêu dữ
 * liệu theo cùng một cách, bất kể event là gì:
 * <ul>
 *   <li>{@code eventId} — định danh DUY NHẤT của lần phát này. Consumer dùng
 *       nó làm khoá idempotency: Kafka là at-least-once, cùng một event có thể
 *       đến hai lần, và {@code eventId} là thứ duy nhất nói được "cái này tôi
 *       xử lý rồi". KHÔNG dùng orderId cho việc này — một đơn sinh ra nhiều
 *       event khác nhau.</li>
 *   <li>{@code eventType} — tên event, để log và định tuyến.</li>
 *   <li>{@code aggregateId} — thực thể mà event nói về (ở đây là orderId).
 *       Cũng là Kafka key, nên mọi event của cùng một đơn rơi vào cùng một
 *       partition và giữ đúng thứ tự.</li>
 *   <li>{@code occurredAt} — thời điểm SỰ VIỆC xảy ra, không phải lúc gửi.</li>
 *   <li>{@code correlationId} — sợi chỉ xuyên suốt một luồng nghiệp vụ qua
 *       nhiều service. Event sinh ra để phản hồi event khác phải MANG TIẾP
 *       correlationId của event gốc, không tạo mới.</li>
 * </ul>
 *
 * <p><b>Vì sao generic {@code <T>} thay vì {@code Object payload}:</b> consumer
 * khai {@code EventEnvelope<OrderCreatedEvent>} và nhận về payload đã có kiểu,
 * không phải một {@code Map} phải tự ép kiểu. Điều này chỉ hoạt động vì mỗi
 * topic chỉ chở ĐÚNG MỘT loại event — xem {@link Topics}.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        String aggregateId,
        Instant occurredAt,
        String correlationId,
        T payload
) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    /**
     * Tạo phong bì mới cho một event. {@code eventId} và {@code occurredAt}
     * luôn được sinh mới — hai lần gọi là hai event khác nhau.
     */
    public static <T> EventEnvelope<T> of(String eventType, String aggregateId,
                                          String correlationId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, aggregateId,
                Instant.now(), correlationId, payload);
    }
}
