package com.orderflow.order.application.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Lệnh đặt hàng — dữ liệu đầu vào của use case.
 *
 * <p>Tách khỏi {@code PlaceOrderRequest} của tầng web có chủ đích: request là
 * hợp đồng HTTP (có thể đổi theo phiên bản API), command là hợp đồng nghiệp vụ.
 * Nhờ đó đổi REST API không kéo theo sửa use case, và ngược lại.
 */
public record PlaceOrderCommand(
        UUID customerId,
        String currency,
        List<Item> items
) {
    public record Item(
            UUID productId,
            String productName,
            int quantity,
            BigDecimal unitPrice
    ) {}
}
