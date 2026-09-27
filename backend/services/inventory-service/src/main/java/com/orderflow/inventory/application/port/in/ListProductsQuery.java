package com.orderflow.inventory.application.port.in;

import com.orderflow.inventory.application.dto.ProductView;

import java.util.List;

/** Danh sách sản phẩm cho catalog — chỉ đọc. */
public interface ListProductsQuery {

    List<ProductView> listProducts();
}
