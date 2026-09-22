package com.orderflow.inventory.application.port.out;

import com.orderflow.inventory.domain.model.StockReservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepositoryPort {

    StockReservation save(StockReservation reservation);

    Optional<StockReservation> findById(UUID id);

    /**
     * Tra phiếu theo cặp (đơn, sản phẩm) — dùng cho IDEMPOTENCY.
     *
     * <p>Event OrderCreated đến lần thứ hai thì tìm thấy phiếu cũ và trả về
     * luôn, không giữ hàng thêm lần nữa.
     */
    Optional<StockReservation> findByOrderIdAndProductId(UUID orderId, UUID productId);

    List<StockReservation> findByOrderId(UUID orderId);

    /** Phiếu HELD đã quá hạn — job dọn dẹp dùng. */
    List<StockReservation> findExpired(Instant now, int limit);
}
