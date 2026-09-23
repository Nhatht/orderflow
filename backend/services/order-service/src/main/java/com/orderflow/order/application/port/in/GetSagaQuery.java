package com.orderflow.order.application.port.in;

import com.orderflow.order.application.dto.SagaView;

import java.util.UUID;

public interface GetSagaQuery {

    /** @throws com.orderflow.order.domain.exception.OrderNotFoundException nếu đơn không có saga */
    SagaView getByOrderId(UUID orderId);
}
