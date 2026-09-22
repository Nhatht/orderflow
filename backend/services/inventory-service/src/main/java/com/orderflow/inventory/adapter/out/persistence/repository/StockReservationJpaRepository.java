package com.orderflow.inventory.adapter.out.persistence.repository;

import com.orderflow.inventory.adapter.out.persistence.entity.StockReservationJpaEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockReservationJpaRepository extends JpaRepository<StockReservationJpaEntity, UUID> {

    Optional<StockReservationJpaEntity> findByOrderIdAndProductId(UUID orderId, UUID productId);

    List<StockReservationJpaEntity> findByOrderId(UUID orderId);

    /** Phiếu HELD đã quá hạn — job dọn dẹp dùng. Index riêng đã có trong V1. */
    @Query("SELECT r FROM StockReservationJpaEntity r "
         + "WHERE r.status = 'HELD' AND r.expiresAt < :now ORDER BY r.expiresAt")
    List<StockReservationJpaEntity> findExpired(@Param("now") Instant now, Limit limit);
}
