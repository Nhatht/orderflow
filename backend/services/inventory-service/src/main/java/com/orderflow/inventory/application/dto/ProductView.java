package com.orderflow.inventory.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Một dòng của catalog: sản phẩm + số còn bán được (tuần 10, cho frontend).
 *
 * <p>Là READ MODEL, không phải domain model: ghép hai bảng {@code products} và
 * {@code stock} chỉ để hiển thị, không có hành vi nghiệp vụ nào cần bảo vệ. Vì
 * vậy nó nằm ở {@code application/dto} và không có class tương ứng ở {@code domain/}
 * (giống {@link StockView}).
 *
 * <p>Chỉ trả {@code availableQty}, không trả {@code reservedQty}: catalog chỉ cần
 * biết "còn thêm vào giỏ được bao nhiêu". Muốn phân biệt "hết thật" với "đang có
 * người giữ" thì dùng {@code GET /stock/{id}}.
 *
 * <p>{@code price} là {@link BigDecimal} (cột NUMERIC(19,4)) — không bao giờ double.
 */
public record ProductView(
        UUID productId,
        String sku,
        String name,
        BigDecimal price,
        String currency,
        int availableQty
) {
}
