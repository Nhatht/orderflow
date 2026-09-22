package com.orderflow.order.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Một dòng hàng trong đơn.
 *
 * <p>Bất biến (immutable): sửa dòng hàng nghĩa là tạo đơn mới, không sửa tại chỗ.
 * Đơn giản hoá được rất nhiều chuyện về đồng thời và audit.
 *
 * <p>{@code unitPrice} được CHỤP LẠI tại thời điểm đặt hàng, không tham chiếu
 * sang bảng giá. Giá sản phẩm đổi ngày mai thì đơn hôm nay vẫn giữ giá cũ —
 * đây là yêu cầu nghiệp vụ bắt buộc, không phải tối ưu.
 */
public record OrderItem(
        UUID id,
        UUID productId,
        String productName,
        int quantity,
        Money unitPrice
) {

    public OrderItem {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(unitPrice, "unitPrice must not be null");

        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got: " + quantity);
        }
        if (unitPrice.isNegative()) {
            throw new IllegalArgumentException("unitPrice must not be negative: " + unitPrice);
        }
    }

    public static OrderItem create(UUID productId, String productName, int quantity, Money unitPrice) {
        return new OrderItem(UUID.randomUUID(), productId, productName, quantity, unitPrice);
    }

    /** Thành tiền của dòng này. */
    public Money subtotal() {
        return unitPrice.multiply(quantity);
    }
}
