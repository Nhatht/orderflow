package com.orderflow.inventory.application.dto;

import com.orderflow.inventory.domain.model.Stock;

import java.util.UUID;

/**
 * Tồn kho nhìn từ bên ngoài.
 *
 * <p>Trả ra CẢ available lẫn reserved có chủ đích: nhờ vậy giao diện phân biệt
 * được "hết hàng thật" (available=0, reserved=0) với "đang có người giữ"
 * (available=0, reserved>0), và nói đúng sự thật với khách thay vì báo
 * "hết hàng" rồi 3 giây sau hàng lại có.
 */
public record StockView(
        UUID productId,
        int availableQty,
        int reservedQty,
        int totalQty
) {
    public static StockView from(Stock s) {
        return new StockView(s.productId(), s.availableQty(), s.reservedQty(), s.totalQty());
    }
}
