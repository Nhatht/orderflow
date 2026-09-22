package com.orderflow.order.application.dto;

import com.orderflow.order.domain.model.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Hình chiếu đơn hàng để trả ra ngoài — chỉ đọc.
 *
 * <p>Không trả thẳng {@link Order} ra khỏi tầng application: domain model có
 * hành vi và bất biến nội bộ, lộ ra ngoài thì tầng web có thể vô tình phụ thuộc
 * vào chi tiết nội bộ của nó.
 */
public record OrderView(
        UUID id,
        UUID customerId,
        String status,
        BigDecimal totalAmount,
        String currency,
        List<ItemView> items,
        Instant createdAt,
        Instant updatedAt
) {
    public record ItemView(
            UUID productId,
            String productName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal
    ) {}

    public static OrderView from(Order order) {
        List<ItemView> itemViews = order.items().stream()
                .map(i -> new ItemView(
                        i.productId(),
                        i.productName(),
                        i.quantity(),
                        i.unitPrice().amount(),
                        i.subtotal().amount()))
                .toList();

        return new OrderView(
                order.id(),
                order.customerId(),
                order.status().name(),
                order.totalAmount().amount(),
                order.currency(),
                itemViews,
                order.createdAt(),
                order.updatedAt());
    }
}
