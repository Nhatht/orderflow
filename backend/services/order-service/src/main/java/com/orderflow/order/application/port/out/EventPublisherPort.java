package com.orderflow.order.application.port.out;

import com.orderflow.order.domain.model.Order;

/**
 * CỔNG RA để phát event nghiệp vụ ra bên ngoài.
 *
 * <p>Nhận {@link Order} (domain model), KHÔNG nhận event contract của Kafka.
 * Việc dịch sang định dạng trên dây là chuyện của adapter — tầng application
 * không cần biết event đi qua Kafka, RabbitMQ hay bảng outbox.
 *
 * <p>Nhờ ranh giới này, tuần 5 thay Kafka trực tiếp bằng Transactional Outbox
 * chỉ bằng một adapter mới; chữ ký interface giữ nguyên. Thứ duy nhất đổi ở
 * tầng application là VỊ TRÍ gọi — xem hợp đồng bên dưới.
 */
public interface EventPublisherPort {

    /**
     * PHẢI được gọi bên trong transaction đang ghi {@code order} — event và
     * đơn cùng commit hoặc cùng rollback. Cài đặt outbox ép điều này bằng
     * {@code Propagation.MANDATORY}: gọi ngoài transaction là exception.
     */
    void publishOrderCreated(Order order);
}
