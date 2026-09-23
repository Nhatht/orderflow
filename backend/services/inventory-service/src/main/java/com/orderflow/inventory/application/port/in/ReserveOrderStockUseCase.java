package com.orderflow.inventory.application.port.in;

import com.orderflow.inventory.application.dto.ReservationOutcome;
import com.orderflow.inventory.application.dto.ReserveOrderStockCommand;

/**
 * Giữ hàng cho cả đơn — bước đầu tiên của saga, được kích hoạt bởi
 * {@code order.created}.
 */
public interface ReserveOrderStockUseCase {

    /**
     * Tất cả hoặc không: mọi dòng hàng cùng được giữ, hoặc không dòng nào.
     * Idempotent theo {@link ReserveOrderStockCommand#idempotencyKey()}.
     */
    ReservationOutcome reserveForOrder(ReserveOrderStockCommand command);
}
