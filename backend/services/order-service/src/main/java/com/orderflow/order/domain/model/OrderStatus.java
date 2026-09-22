package com.orderflow.order.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Trạng thái đơn hàng — đồng thời là bộ khung của saga.
 *
 * <p>Luồng thuận:
 * <pre>
 *   PENDING → STOCK_RESERVED → PAID → CONFIRMED
 * </pre>
 *
 * <p>Bất kỳ trạng thái nào trước CONFIRMED đều có thể rơi về CANCELLED
 * (đó chính là nhánh compensate của saga). CONFIRMED và CANCELLED là
 * trạng thái cuối, không đi tiếp được nữa.
 *
 * <p>Đặt luật chuyển trạng thái NGAY TRONG enum thay vì rải if/else khắp
 * service: chỉ có một chỗ duy nhất định nghĩa "đi từ đâu tới đâu là hợp lệ",
 * nên không thể quên cập nhật khi thêm trạng thái mới.
 */
public enum OrderStatus {

    /** Đơn vừa tạo, chưa giữ được hàng. */
    PENDING,

    /** Inventory đã giữ hàng thành công. */
    STOCK_RESERVED,

    /** Thanh toán xong, chờ chốt đơn. */
    PAID,

    /** Trạng thái cuối — thành công. */
    CONFIRMED,

    /** Trạng thái cuối — đã huỷ, mọi bước trước đó đã được đền bù. */
    CANCELLED;

    public boolean isFinal() {
        return this == CONFIRMED || this == CANCELLED;
    }

    public boolean canTransitionTo(OrderStatus target) {
        return allowedTransitions().contains(target);
    }

    private Set<OrderStatus> allowedTransitions() {
        return switch (this) {
            case PENDING        -> EnumSet.of(STOCK_RESERVED, CANCELLED);
            case STOCK_RESERVED -> EnumSet.of(PAID, CANCELLED);
            case PAID           -> EnumSet.of(CONFIRMED, CANCELLED);
            case CONFIRMED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }
}
