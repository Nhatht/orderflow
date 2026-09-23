package com.orderflow.contracts.order;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Đơn hàng vừa được tạo — mở màn của saga.
 *
 * <p>Event mang ĐỦ dữ liệu để inventory làm việc mà không phải gọi ngược lại
 * order-service hỏi "đơn này có gì". Đây gọi là <i>event-carried state
 * transfer</i>: gọi ngược lại sẽ tạo phụ thuộc đồng bộ, và order-service chết
 * thì inventory cũng không xử lý được — mất luôn lợi ích của việc dùng Kafka.
 *
 * <p><b>Tiền serialize thành CHUỖI</b> ({@code "45000.0000"}), không phải số.
 * JSON number bị JavaScript đọc thành {@code double} — frontend ở tuần 9–11 sẽ
 * nhận sai số làm tròn nếu để dạng số. Chuỗi thì bên nhận tự chọn kiểu chính
 * xác của ngôn ngữ mình ({@code BigDecimal}, {@code decimal}, thư viện big.js).
 */
public record OrderCreatedEvent(
        UUID orderId,
        UUID customerId,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
        List<Item> items
) {

    public static final String TYPE = "OrderCreated";

    public OrderCreatedEvent {
        items = List.copyOf(items);
    }

    public record Item(
            UUID productId,
            String productName,
            int quantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice
    ) {}
}
