package com.orderflow.inventory.application.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Lệnh giữ hàng cho TOÀN BỘ một đơn.
 *
 * @param idempotencyKey khoá chống xử lý trùng. Khi lệnh đến từ Kafka, đây là
 *                       {@code eventId} của event. Đặt tên theo VAI TRÒ chứ
 *                       không theo nguồn gốc: tầng application không cần biết
 *                       lệnh đến từ Kafka, REST hay batch job — nó chỉ cần
 *                       biết "hai lệnh cùng khoá này là một".
 * @param correlationId  mang tiếp sang event kết quả, để lần theo cả luồng
 *                       nghiệp vụ qua nhiều service.
 */
public record ReserveOrderStockCommand(
        UUID idempotencyKey,
        String correlationId,
        UUID orderId,
        List<Item> items
) {

    public ReserveOrderStockCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        items = List.copyOf(items);
    }

    public record Item(UUID productId, int quantity) {}
}
