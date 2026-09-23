package com.orderflow.order.application.port.out;

import com.orderflow.order.domain.model.Order;

/**
 * CỔNG RA để phát event nghiệp vụ ra bên ngoài.
 *
 * <p>Nhận {@link Order} (domain model), KHÔNG nhận event contract của Kafka.
 * Việc dịch sang định dạng trên dây là chuyện của adapter — tầng application
 * không cần biết event đi qua Kafka, RabbitMQ hay bảng outbox.
 *
 * <p>Chính nhờ ranh giới này mà tuần 5 thay Kafka trực tiếp bằng Transactional
 * Outbox chỉ cần viết adapter mới; {@code PlaceOrderService} không sửa gì.
 */
public interface EventPublisherPort {

    void publishOrderCreated(Order order);
}
