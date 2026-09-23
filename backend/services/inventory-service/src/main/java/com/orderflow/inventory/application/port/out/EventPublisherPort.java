package com.orderflow.inventory.application.port.out;

import com.orderflow.inventory.application.dto.ReservationOutcome;
import com.orderflow.inventory.application.dto.ReservationView;

import java.util.List;
import java.util.UUID;

/**
 * CỔNG RA để báo kết quả giữ hàng cho phần còn lại của hệ thống.
 *
 * <p>Nằm ở tầng application (không phải listener) vì QUYẾT ĐỊNH phát event gì
 * là quyết định nghiệp vụ. Tuần 5 còn cần việc ghi event diễn ra TRONG
 * transaction giữ hàng (Outbox) — chỉ tầng application nắm được ranh giới đó.
 */
public interface EventPublisherPort {

    /** PHẢI gọi trong transaction giữ hàng — cài đặt outbox ép bằng MANDATORY. */
    void publishStockReserved(ReservationOutcome.Reserved outcome, String correlationId);

    /** PHẢI gọi trong transaction ghi {@code processed_events}. */
    void publishStockReservationFailed(ReservationOutcome.Rejected outcome, String correlationId);

    /** PHẢI gọi trong transaction nhả hàng. {@code released} có thể rỗng — xem {@code StockReleasedEvent}. */
    void publishStockReleased(UUID orderId, List<ReservationView> released, String correlationId);
}
