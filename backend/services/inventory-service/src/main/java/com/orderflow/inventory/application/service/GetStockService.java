package com.orderflow.inventory.application.service;

import com.orderflow.inventory.application.dto.StockView;
import com.orderflow.inventory.application.port.in.GetStockQuery;
import com.orderflow.inventory.application.port.out.StockCachePort;
import com.orderflow.inventory.application.port.out.StockRepositoryPort;
import com.orderflow.inventory.domain.exception.StockNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * CACHE-ASIDE (pattern 6): ứng dụng tự quản lý cache, database là nguồn sự thật.
 * <pre>
 *   đọc:  cache có? → trả luôn
 *         không có  → đọc DB → ghi vào cache (kèm TTL) → trả
 *   ghi:  ghi DB (+ event stock.changed trong outbox, cùng transaction)
 *         → listener nhận event → XOÁ cache (không cập nhật)
 * </pre>
 *
 * <p><b>Vì sao xoá chứ không cập nhật cache khi có thay đổi:</b> hai lần ghi
 * đồng thời có thể cập nhật cache theo thứ tự ngược với thứ tự commit DB → cache
 * giữ giá trị cũ mãi. Xoá thì thứ tự không quan trọng: lần đọc sau luôn lấy từ DB.
 *
 * <p>Không có {@code @Transactional}: đường cache hit không cần connection DB —
 * đó là toàn bộ lợi ích của cache.
 */
@Service
@RequiredArgsConstructor
public class GetStockService implements GetStockQuery {

    private final StockRepositoryPort stockRepository;
    private final StockCachePort cache;

    @Override
    public StockView getByProductId(UUID productId) {
        return cache.get(productId).orElseGet(() -> {
            StockView fresh = stockRepository.findByProductId(productId)
                    .map(StockView::from)
                    .orElseThrow(() -> new StockNotFoundException(productId));
            cache.put(fresh);
            return fresh;
        });
    }
}
