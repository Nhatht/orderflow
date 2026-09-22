package com.orderflow.inventory.domain.exception;

import java.util.UUID;

/**
 * Không đủ hàng để giữ.
 *
 * <p>Đây là kết quả NGHIỆP VỤ BÌNH THƯỜNG, không phải lỗi hệ thống: hết hàng
 * là chuyện xảy ra hằng ngày. Saga bắt exception này rồi đi nhánh compensate,
 * và API trả 409 chứ không phải 500.
 */
public class InsufficientStockException extends RuntimeException {

    private final UUID productId;
    private final int requested;
    private final int available;

    public InsufficientStockException(UUID productId, int requested, int available) {
        super("Insufficient stock for product %s: requested %d, available %d"
                .formatted(productId, requested, available));
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    public UUID productId() { return productId; }
    public int requested()  { return requested; }
    public int available()  { return available; }
}
