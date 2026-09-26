package com.orderflow.inventory.adapter.out.persistence;

import com.orderflow.inventory.adapter.out.persistence.mapper.InventoryMapper;
import com.orderflow.inventory.adapter.out.persistence.repository.StockJpaRepository;
import com.orderflow.inventory.application.port.out.EventPublisherPort;
import com.orderflow.inventory.application.port.out.StockRepositoryPort;
import com.orderflow.inventory.domain.model.Stock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lưu tồn kho — và mỗi lần lưu là một event {@code stock.changed} vào outbox.
 *
 * <p><b>Vì sao phát event ở TẦNG LƯU chứ không ở từng use case:</b> tồn kho được
 * ghi ở 6 chỗ trong 4 service (giữ đơn, giữ lẻ, nhả, chốt, hết hạn, lấy lại sau
 * hết hạn). Bắt từng chỗ nhớ phát event thì chỗ thứ 7 thêm sau này sẽ quên, và
 * cache của sản phẩm đó cũ tới hết TTL mà không ai biết. Gắn vào tầng lưu thì
 * KHÔNG CÓ CÁCH nào đổi tồn kho mà không phát — cùng ý tưởng với Change Data
 * Capture (Debezium đọc WAL của Postgres), nhưng rẻ hơn nhiều.
 *
 * <p>Cái giá: event phát cả khi lưu mà số lượng không đổi (nhánh OVERSOLD). Vô
 * hại — xoá cache thừa một lần.
 */
@Component
@RequiredArgsConstructor
public class StockPersistenceAdapter implements StockRepositoryPort {

    private final StockJpaRepository repository;
    private final InventoryMapper mapper;
    private final EventPublisherPort eventPublisher;

    @Override
    public Stock save(Stock stock) {
        var entity = repository.findById(stock.productId())
                .map(existing -> {
                    mapper.updateEntity(existing, stock);
                    return existing;
                })
                .orElseGet(() -> mapper.toEntity(stock));

        Stock saved = mapper.toDomain(repository.save(entity));
        eventPublisher.publishStockChanged(saved);   // MANDATORY: cùng transaction với thay đổi
        return saved;
    }

    @Override
    public Optional<Stock> findByProductId(UUID productId) {
        return repository.findById(productId).map(mapper::toDomain);
    }

    @Override
    public List<Stock> findByProductIds(List<UUID> productIds) {
        return repository.findByProductIdIn(productIds).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
