package com.orderflow.inventory.application.port.out;

import com.orderflow.inventory.application.dto.ReservationOutcome;

/**
 * CỔNG RA để báo kết quả giữ hàng cho phần còn lại của hệ thống.
 *
 * <p>Nằm ở tầng application (không phải listener) vì QUYẾT ĐỊNH phát event gì
 * là quyết định nghiệp vụ. Tuần 5 còn cần việc ghi event diễn ra TRONG
 * transaction giữ hàng (Outbox) — chỉ tầng application nắm được ranh giới đó.
 */
public interface EventPublisherPort {

    void publishStockReserved(ReservationOutcome.Reserved outcome, String correlationId);

    void publishStockReservationFailed(ReservationOutcome.Rejected outcome, String correlationId);
}
