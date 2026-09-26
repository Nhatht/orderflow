package com.orderflow.inventory.application.port.out;

import com.orderflow.inventory.application.dto.StockView;

import java.util.Optional;
import java.util.UUID;

/**
 * Cache tồn kho để HIỂN THỊ — pattern 6, cache-aside.
 *
 * <p><b>Chỉ dùng cho đường đọc.</b> Mọi quyết định giữ/nhả/chốt hàng đọc thẳng
 * database trong khoá — cache lệch vài trăm mili giây không bao giờ gây oversell.
 */
public interface StockCachePort {

    /** Rỗng khi cache miss HOẶC khi Redis lỗi — người gọi đọc database. */
    Optional<StockView> get(UUID productId);

    /** Lỗi thì bỏ qua: không cache được chỉ làm chậm lần đọc sau, không sai. */
    void put(StockView stock);

    /** Lỗi thì NÉM: xoá thất bại mà im lặng là để lại dữ liệu cũ — phải thử lại. */
    void evict(UUID productId);
}
