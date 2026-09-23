package com.orderflow.contracts.order;

import java.util.UUID;

/**
 * Saga kết thúc thành công — hàng đã giữ, tiền đã thu.
 *
 * <p>Không mang danh sách sản phẩm: inventory tự tra phiếu giữ hàng của đơn
 * trong database của nó. Nhét danh sách vào đây thì hai nguồn sự thật có thể
 * lệch nhau.
 *
 * @param customerId thêm ở tuần 7 cho notification-service (gửi mail cho ai).
 *                   Thêm field là thay đổi TƯƠNG THÍCH TIẾN: consumer đời cũ
 *                   bỏ qua field lạ (xem {@code EventEnvelopeSerializationTest}).
 */
public record OrderConfirmedEvent(UUID orderId, UUID customerId) {
    public static final String TYPE = "OrderConfirmed";
}
