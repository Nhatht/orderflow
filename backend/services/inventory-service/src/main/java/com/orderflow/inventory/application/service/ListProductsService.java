package com.orderflow.inventory.application.service;

import com.orderflow.inventory.application.dto.ProductView;
import com.orderflow.inventory.application.port.in.ListProductsQuery;
import com.orderflow.inventory.application.port.out.ProductCatalogPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Catalog cho frontend (tuần 10).
 *
 * <p><b>KHÔNG cache, có chủ đích</b> — khác với {@link GetStockService}:
 * <ul>
 *   <li>Danh sách chứa {@code availableQty} của MỌI sản phẩm. Mỗi lần giữ/nhả/chốt
 *       hàng bất kỳ đều làm nó cũ. Muốn cache đúng thì listener {@code stock.changed}
 *       phải xoá thêm khoá danh sách — khoá "gộp" bị xoá gần như liên tục thì tỉ lệ
 *       trúng cache thấp, chỉ thêm độ phức tạp.</li>
 *   <li>Câu đọc rẻ: một JOIN trên hai bảng nhỏ theo khoá chính.</li>
 *   <li>Catalog là nơi khách quyết định mua — số cũ dẫn tới "thêm vào giỏ được nhưng
 *       đặt thì hết hàng". Không gây oversell (giữ hàng luôn đọc DB trong khoá),
 *       nhưng là trải nghiệm tồi.</li>
 * </ul>
 * Khi nào nên cache: catalog lớn (phân trang, tìm kiếm) → cache PHẦN TĨNH (tên, giá)
 * dài hạn, còn tồn kho ghép từ cache từng sản phẩm đã có ở {@code /stock}.
 */
@Service
@RequiredArgsConstructor
public class ListProductsService implements ListProductsQuery {

    private final ProductCatalogPort catalog;

    @Override
    public List<ProductView> listProducts() {
        return catalog.findAllWithAvailability();
    }
}
