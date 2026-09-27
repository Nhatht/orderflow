package com.orderflow.inventory.application.port.out;

import com.orderflow.inventory.application.dto.ProductView;

import java.util.List;

/**
 * Đọc catalog (sản phẩm + tồn kho còn bán) từ nơi lưu trữ.
 *
 * <p>Port riêng thay vì thêm vào {@link StockRepositoryPort}: {@code StockRepositoryPort}
 * phục vụ đường GHI (giữ/nhả/chốt hàng, có khoá và outbox); đây là đường ĐỌC thuần
 * để hiển thị. Tách ra thì adapter đọc được tối ưu riêng (một câu JOIN, trả thẳng
 * read model) mà không chạm vào đường ghi.
 */
public interface ProductCatalogPort {

    /** Mọi sản phẩm, sắp theo SKU. Sản phẩm chưa có dòng tồn kho → availableQty = 0. */
    List<ProductView> findAllWithAvailability();
}
