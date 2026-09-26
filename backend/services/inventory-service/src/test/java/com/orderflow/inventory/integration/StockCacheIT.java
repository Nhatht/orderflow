package com.orderflow.inventory.integration;

import com.orderflow.inventory.application.dto.ReservationOutcome;
import com.orderflow.inventory.application.dto.ReserveOrderStockCommand;
import com.orderflow.inventory.application.port.in.GetStockQuery;
import com.orderflow.inventory.application.port.in.ReserveOrderStockUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PATTERN 6 — cache-aside + invalidation qua event, trên Redis và Kafka THẬT.
 */
class StockCacheIT extends AbstractInventoryIT {

    @Autowired GetStockQuery getStock;
    @Autowired ReserveOrderStockUseCase reserveOrderStock;
    @Autowired RedissonClient redisson;

    @Test
    @DisplayName("Lần đọc đầu miss → đọc DB và ghi cache; lần sau trả từ cache, không đụng DB")
    void secondReadIsServedFromCache() {
        UUID product = givenProductWithStock(10);

        assertThat(getStock.getByProductId(product).availableQty()).isEqualTo(10);
        assertThat(cached(product)).as("miss → ghi vào Redis").isNotNull();

        // Sửa DB SAU LƯNG ứng dụng (không qua code → không có event). Lần đọc sau
        // vẫn thấy 10: bằng chứng nó đến từ cache, không phải từ database.
        jdbc.update("UPDATE stock SET available_qty = 3 WHERE product_id = ?", product);
        assertThat(getStock.getByProductId(product).availableQty()).isEqualTo(10);
    }

    @Test
    @DisplayName("Giữ hàng → stock.changed qua outbox → cache bị XOÁ → lần đọc sau thấy số mới")
    void stockChangeEvictsCache() throws Exception {
        UUID product = givenProductWithStock(10);
        getStock.getByProductId(product);                 // nạp cache: available=10
        assertThat(cached(product)).isNotNull();

        var outcome = reserveOrderStock.reserveForOrder(new ReserveOrderStockCommand(
                UUID.randomUUID(), "c", UUID.randomUUID(), List.of(new ReserveOrderStockCommand.Item(product, 3))));
        assertThat(outcome).isInstanceOf(ReservationOutcome.Reserved.class);

        awaitEvicted(product);
        assertThat(getStock.getByProductId(product).availableQty()).isEqualTo(7);
    }

    @Test
    @DisplayName("Mỗi lần ghi tồn kho đều có đúng một dòng stock.changed trong outbox, CÙNG transaction")
    void everyStockWriteEmitsStockChanged() {
        UUID product = givenProductWithStock(10);
        reserveOrderStock.reserveForOrder(new ReserveOrderStockCommand(
                UUID.randomUUID(), "c", UUID.randomUUID(), List.of(new ReserveOrderStockCommand.Item(product, 2))));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE topic = 'stock.changed' AND aggregate_id = ?",
                Integer.class, product.toString())).isEqualTo(1);
    }

    // ---- helper ------------------------------------------------------------

    private String cached(UUID product) {
        return redisson.<String>getBucket("orderflow:cache:stock:" + product, StringCodec.INSTANCE).get();
    }

    private void awaitEvicted(UUID product) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (cached(product) != null && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(cached(product)).as("cache phải bị xoá bởi event stock.changed").isNull();
    }
}
