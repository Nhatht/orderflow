package com.orderflow.inventory.application.service;

import com.orderflow.inventory.application.dto.StockView;
import com.orderflow.inventory.application.port.in.GetStockQuery;
import com.orderflow.inventory.application.port.out.StockRepositoryPort;
import com.orderflow.inventory.domain.exception.StockNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GetStockService implements GetStockQuery {

    private final StockRepositoryPort stockRepository;

    @Override
    @Transactional(readOnly = true)
    public StockView getByProductId(UUID productId) {
        return stockRepository.findByProductId(productId)
                .map(StockView::from)
                .orElseThrow(() -> new StockNotFoundException(productId));
    }
}
