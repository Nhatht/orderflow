package com.orderflow.contracts.inventory;

import java.util.List;
import java.util.UUID;

/**
 * Inventory đã nhả hàng của đơn về kho — BƯỚC ĐỀN BÙ ĐÃ HOÀN TẤT.
 *
 * <p>Vì sao cần event này thay vì order-service bắn {@code order.cancelled} rồi
 * coi như xong: bắn lệnh không có nghĩa là lệnh đã được thực hiện. Có event xác
 * nhận thì saga chuyển được từ COMPENSATING sang COMPENSATED một cách có căn
 * cứ, và trang saga timeline ở frontend hiển thị được khoảnh khắc hàng thật
 * sự quay về kho.
 *
 * <p>Phát khi saga đang CHỜ xác nhận đền bù (đơn huỷ vì thanh toán thất bại) —
 * kể cả khi không còn gì để nhả (phiếu đã hết hạn, hoặc đã nhả ở lần giao
 * trước): khi đó {@code items} rỗng. Thiếu event này thì saga treo ở
 * COMPENSATING mãi. Đơn huỷ vì hết hàng thì inventory
 * chưa giữ gì và saga không chờ, nên không phát.
 */
public record StockReleasedEvent(UUID orderId, List<ReleasedItem> items) {

    public static final String TYPE = "StockReleased";

    public StockReleasedEvent {
        items = List.copyOf(items);
    }

    public record ReleasedItem(UUID productId, int quantity) {}
}
