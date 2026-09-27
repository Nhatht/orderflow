package com.orderflow.inventory.integration;

import com.orderflow.inventory.application.dto.ProductView;
import com.orderflow.inventory.application.dto.ReservationOutcome;
import com.orderflow.inventory.application.dto.ReserveOrderStockCommand;
import com.orderflow.inventory.application.port.in.ListProductsQuery;
import com.orderflow.inventory.application.port.in.ReserveOrderStockUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TUẦN 10 — {@code GET /api/inventory/products} cho catalog của frontend,
 * trên PostgreSQL thật (câu JOIN chạy đúng dialect, NUMERIC giữ nguyên scale).
 */
class ProductCatalogIT extends AbstractInventoryIT {

    @Autowired ListProductsQuery listProducts;
    @Autowired ReserveOrderStockUseCase reserveOrderStock;

    @Test
    @DisplayName("Sản phẩm có trong catalog với giá BigDecimal đúng từng chữ số và số còn bán")
    void listsProductWithExactPriceAndAvailability() {
        UUID product = givenProductWithStock(7);

        ProductView view = find(product);

        assertThat(view.sku()).isEqualTo("TEST-" + product.toString().substring(0, 8));
        assertThat(view.name()).isEqualTo("Test product");
        assertThat(view.currency()).isEqualTo("VND");
        // So bằng equals (tính cả scale), không phải compareTo: NUMERIC(19,4) phải về
        // đúng "10000.0000" — không qua double ở đâu cả.
        assertThat(view.price()).isEqualTo(new BigDecimal("10000.0000"));
        assertThat(view.availableQty()).isEqualTo(7);
    }

    @Test
    @DisplayName("Không cache: giữ hàng xong đọc lại catalog NGAY thấy số mới")
    void reflectsReservationImmediately() {
        UUID product = givenProductWithStock(10);
        assertThat(find(product).availableQty()).isEqualTo(10);

        var outcome = reserveOrderStock.reserveForOrder(new ReserveOrderStockCommand(
                UUID.randomUUID(), "c", UUID.randomUUID(),
                List.of(new ReserveOrderStockCommand.Item(product, 4))));
        assertThat(outcome).isInstanceOf(ReservationOutcome.Reserved.class);

        // Không chờ event stock.changed nào — khác StockCacheIT.
        assertThat(find(product).availableQty()).isEqualTo(6);
    }

    @Test
    @DisplayName("Sản phẩm chưa có dòng tồn kho vẫn hiện, với số lượng 0 (LEFT JOIN)")
    void productWithoutStockRowShowsZero() {
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, sku, name, price, currency) VALUES (?, ?, ?, ?, ?)",
                product, "NOSTOCK-" + product.toString().substring(0, 8),
                "No stock", new BigDecimal("1.5000"), "VND");

        assertThat(find(product).availableQty()).isZero();
    }

    @Test
    @DisplayName("Danh sách sắp theo SKU — thứ tự ổn định giữa các lần gọi")
    void sortedBySku() {
        givenProductWithStock(1);
        givenProductWithStock(1);

        assertThat(listProducts.listProducts())
                .extracting(ProductView::sku)
                .isSortedAccordingTo(Comparator.naturalOrder());
    }

    private ProductView find(UUID productId) {
        return listProducts.listProducts().stream()
                .filter(p -> p.productId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("product " + productId + " not in catalog"));
    }
}
