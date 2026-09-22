package com.orderflow.inventory.application.port.in;

import com.orderflow.inventory.application.dto.ReservationView;
import com.orderflow.inventory.application.dto.ReserveStockCommand;

import java.util.UUID;

public interface ReserveStockUseCase {

    /** Giữ hàng. Idempotent: gọi lại cùng (orderId, productId) trả về phiếu cũ. */
    ReservationView reserve(ReserveStockCommand command);

    /** Nhả hàng đã giữ — bước compensate của saga. */
    void release(UUID orderId, UUID productId);

    /** Chốt: hàng rời kho thật sự. */
    void confirm(UUID orderId, UUID productId);
}
