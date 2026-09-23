package com.orderflow.contracts.inventory;

import java.util.List;
import java.util.UUID;

/**
 * Phiếu giữ hàng của đơn đã HẾT HẠN và hàng đã được trả về kho.
 *
 * <p>Khác {@link StockReleasedEvent}: {@code stock.released} là inventory TRẢ LỜI
 * lệnh đền bù của saga ("anh bảo nhả, tôi đã nhả"). Event này thì inventory TỰ
 * phát, không ai yêu cầu — saga có thể đang chờ thanh toán và không hề biết
 * hàng vừa bị lấy lại. Trộn hai thứ vào một topic thì saga không phân biệt được
 * "đền bù xong" với "hàng bị thu hồi dưới chân mình".
 *
 * <p>Saga nhận event này khi đang chờ thanh toán thì huỷ đơn. Nếu tiền về sau
 * đó, đó là late response — cần hoàn tiền (xem {@code docs/PATTERNS.md} mục 0).
 */
public record StockReservationExpiredEvent(UUID orderId, List<ExpiredItem> items) {

    public static final String TYPE = "StockReservationExpired";

    public StockReservationExpiredEvent {
        items = List.copyOf(items);
    }

    public record ExpiredItem(UUID productId, int quantity) {}
}
