package com.orderflow.contracts.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inventory đã giữ đủ hàng cho TOÀN BỘ đơn.
 *
 * <p>Không có trạng thái "giữ được một nửa": hoặc mọi dòng hàng đều được giữ
 * và event này được phát, hoặc không dòng nào được giữ và
 * {@link StockReservationFailedEvent} được phát. Inventory làm được điều đó vì
 * mọi dòng hàng nằm trong CÙNG một database — dùng transaction ACID cục bộ
 * cho phần đó, chỉ dùng saga cho phần xuyên service.
 *
 * @param expiresAt hạn chót của phiếu giữ hàng. Quá hạn mà saga chưa chốt
 *                  thì hàng tự được nhả về kho.
 */
public record StockReservedEvent(
        UUID orderId,
        List<ReservedItem> items,
        Instant expiresAt
) {

    public static final String TYPE = "StockReserved";

    public StockReservedEvent {
        items = List.copyOf(items);
    }

    public record ReservedItem(UUID reservationId, UUID productId, int quantity) {}
}
