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
 *
 * <p><b>Mọi method PHẢI được gọi bên trong transaction đang ghi thay đổi
 * tương ứng</b> — event và dữ liệu cùng commit hoặc cùng rollback. Cài đặt
 * outbox ép điều này bằng {@code Propagation.MANDATORY}.
 */
public interface EventPublisherPort {

    /** Mở đầu saga — đồng thời là lệnh "hãy giữ hàng" gửi inventory. */
    void publishOrderCreated(Order order);

    /** Lệnh thu tiền. {@code correlationId} mang tiếp từ phản hồi của inventory. */
    void publishPaymentRequested(Order order, String correlationId);

    void publishOrderConfirmed(Order order, String correlationId);

    void publishOrderCancelled(Order order, CancellationReason reason, String correlationId);

    enum CancellationReason { STOCK_UNAVAILABLE, PAYMENT_DECLINED }
}
