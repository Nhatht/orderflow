package com.orderflow.order.application.port.in;

import com.orderflow.order.application.dto.OrderView;
import com.orderflow.order.application.dto.PlaceOrderCommand;

/**
 * CỔNG VÀO (driving port) — mô tả hệ thống LÀM ĐƯỢC GÌ.
 *
 * <p>Tầng adapter (REST controller, Kafka listener, CLI...) gọi interface này.
 * Chúng không biết gì về implementation, chỉ biết hợp đồng.
 */
public interface PlaceOrderUseCase {

    OrderView placeOrder(PlaceOrderCommand command);
}
