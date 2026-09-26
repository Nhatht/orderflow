package com.orderflow.contracts.inventory;

import java.util.UUID;

/**
 * Tồn kho của MỘT sản phẩm vừa đổi (giữ, nhả, chốt, hết hạn...) — pattern 6,
 * cache-aside + invalidation qua event.
 *
 * <p>Mang theo số lượng mới cho tiện gỡ lỗi và cho consumer tương lai (vd. đẩy
 * realtime ra frontend), nhưng consumer cache KHÔNG được dùng các số này để
 * GHI ĐÈ cache: event có thể tới trễ hoặc lệch thứ tự so với lần đọc DB mới
 * hơn. Nó chỉ XOÁ cache — lần đọc sau tự lấy số mới từ database.
 */
public record StockChangedEvent(UUID productId, int availableQty, int reservedQty) {

    public static final String TYPE = "StockChanged";
}
