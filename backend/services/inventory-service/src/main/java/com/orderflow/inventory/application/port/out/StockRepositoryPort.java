package com.orderflow.inventory.application.port.out;

import com.orderflow.inventory.domain.model.Stock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockRepositoryPort {

    Stock save(Stock stock);

    Optional<Stock> findByProductId(UUID productId);

    List<Stock> findByProductIds(List<UUID> productIds);
}
