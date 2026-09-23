package com.orderflow.inventory.application.port.in;

import java.util.UUID;

/**
 * Chốt số phận hàng đã giữ của một đơn, khi saga đã có kết cục.
 *
 * <p>Cả hai idempotent theo {@code idempotencyKey} (eventId), và an toàn khi
 * đơn không có phiếu giữ nào (đơn huỷ vì hết hàng): không có gì thì không sửa tồn kho.
 */
public interface SettleOrderStockUseCase {

    /** Đơn đã chốt: hàng đang giữ rời kho thật sự. */
    void confirmForOrder(UUID idempotencyKey, String correlationId, UUID orderId);

    /** Đơn đã huỷ: nhả hàng đang giữ về kho — BƯỚC ĐỀN BÙ của saga. */
    void releaseForOrder(UUID idempotencyKey, String correlationId, UUID orderId, boolean sagaAwaitsAck);
}
