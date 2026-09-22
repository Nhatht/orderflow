package com.orderflow.inventory.application.port.in;

import com.orderflow.inventory.application.dto.StockView;

import java.util.UUID;

public interface GetStockQuery {

    StockView getByProductId(UUID productId);
}
