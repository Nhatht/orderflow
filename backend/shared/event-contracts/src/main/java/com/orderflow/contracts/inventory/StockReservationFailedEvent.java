package com.orderflow.contracts.inventory;

import java.util.List;
import java.util.UUID;

/**
 * Inventory KHÔNG giữ được hàng cho đơn — saga phải huỷ đơn.
 *
 * <p>Đây là kết quả nghiệp vụ bình thường (hết hàng), không phải lỗi hệ thống.
 * Lỗi hệ thống (database sập, mất kết nối Redis) KHÔNG sinh ra event này —
 * chúng làm consumer ném exception để Kafka giao lại message sau. Trộn hai
 * loại vào nhau thì một lần Redis chập chờn sẽ huỷ oan hàng loạt đơn hợp lệ.
 *
 * @param shortages dòng hàng nào thiếu, thiếu bao nhiêu — để frontend hiển thị
 *                  "Kẹo dừa chỉ còn 2" thay vì "đặt hàng thất bại" chung chung.
 */
public record StockReservationFailedEvent(
        UUID orderId,
        Reason reason,
        List<Shortage> shortages
) {

    public static final String TYPE = "StockReservationFailed";

    public StockReservationFailedEvent {
        shortages = List.copyOf(shortages);
    }

    public enum Reason {
        /** Có ít nhất một sản phẩm không đủ số lượng. */
        INSUFFICIENT_STOCK,
        /** Có sản phẩm không tồn tại trong kho. */
        UNKNOWN_PRODUCT
    }

    /** {@code available} = 0 khi sản phẩm không tồn tại. */
    public record Shortage(UUID productId, int requested, int available) {}
}
