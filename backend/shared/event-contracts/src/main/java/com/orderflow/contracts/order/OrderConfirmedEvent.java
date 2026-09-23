package com.orderflow.contracts.order;

import java.util.UUID;

/**
 * Saga kết thúc thành công — hàng đã giữ, tiền đã thu.
 *
 * <p>Chỉ mang orderId: inventory tự tra phiếu giữ hàng của đơn trong database
 * của nó. Nhét danh sách sản phẩm vào đây thì hai nguồn sự thật có thể lệch nhau.
 */
public record OrderConfirmedEvent(UUID orderId) {
    public static final String TYPE = "OrderConfirmed";
}
