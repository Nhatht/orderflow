package com.orderflow.inventory.application.port.in;

import java.time.Instant;

/**
 * Nhả hàng của các phiếu giữ đã quá hạn — lưới an toàn cho những đơn không
 * bao giờ được chốt hay huỷ (saga kẹt, service chết, đơn tạo trước khi có saga).
 */
public interface ExpireReservationsUseCase {

    /**
     * Xử lý một lô phiếu HELD có {@code expires_at < now}.
     *
     * <p>An toàn khi chạy song song trên nhiều instance: mỗi đơn được khoá và
     * đọc lại trạng thái trước khi nhả, nên một phiếu chỉ được nhả đúng một lần.
     *
     * @return số phiếu thật sự được chuyển sang EXPIRED bởi lần gọi này
     */
    int expireDue(Instant now);
}
