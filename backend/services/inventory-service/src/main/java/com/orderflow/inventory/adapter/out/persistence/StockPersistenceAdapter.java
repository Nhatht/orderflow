package com.orderflow.inventory.adapter.out.persistence;

import com.orderflow.inventory.adapter.out.persistence.mapper.InventoryMapper;
import com.orderflow.inventory.adapter.out.persistence.repository.StockJpaRepository;
import com.orderflow.inventory.application.port.out.StockRepositoryPort;
import com.orderflow.inventory.domain.model.Stock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StockPersistenceAdapter implements StockRepositoryPort {

    private final StockJpaRepository repository;
    private final InventoryMapper mapper;

    @Override
    public Stock save(Stock stock) {
        var entity = repository.findById(stock.productId())
                .map(existing -> {
                    mapper.updateEntity(existing, stock);
                    return existing;
                })
                .orElseGet(() -> mapper.toEntity(stock));

        return mapper.toDomain(repository.save(entity));
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
