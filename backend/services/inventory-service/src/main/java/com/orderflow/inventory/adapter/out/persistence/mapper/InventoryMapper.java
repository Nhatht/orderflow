package com.orderflow.inventory.adapter.out.persistence.mapper;

import com.orderflow.inventory.adapter.out.persistence.entity.StockJpaEntity;
import com.orderflow.inventory.adapter.out.persistence.entity.StockReservationJpaEntity;
import com.orderflow.inventory.domain.model.ReservationStatus;
import com.orderflow.inventory.domain.model.Stock;
import com.orderflow.inventory.domain.model.StockReservation;
import org.springframework.stereotype.Component;

/** Chuyển đổi domain ↔ entity JPA. */
@Component
public class InventoryMapper {

    // ---- Stock -------------------------------------------------------------

    public StockJpaEntity toEntity(Stock stock) {
        return new StockJpaEntity(
                stock.productId(), stock.availableQty(), stock.reservedQty(),
                stock.version(), stock.updatedAt());
    }

    /**
     * Cập nhật entity đang được Hibernate quản lý.
     *
     * <p>KHÔNG set lại {@code version}: Hibernate tự quản cột đó. Gán tay sẽ
     * phá cơ chế optimistic locking — đúng thứ ta đang dựa vào để chống oversell.
     */
    public void updateEntity(StockJpaEntity entity, Stock stock) {
        entity.setAvailableQty(stock.availableQty());
        entity.setReservedQty(stock.reservedQty());
        entity.setUpdatedAt(stock.updatedAt());
    }

    public Stock toDomain(StockJpaEntity e) {
        return new Stock(e.getProductId(), e.getAvailableQty(), e.getReservedQty(),
                e.getUpdatedAt(), e.getVersion());
    }

    // ---- StockReservation --------------------------------------------------

    public StockReservationJpaEntity toEntity(StockReservation r) {
        return new StockReservationJpaEntity(
                r.id(), r.orderId(), r.productId(), r.quantity(),
                r.status().name(), r.expiresAt(), r.createdAt(), r.updatedAt());
    }

    public void updateEntity(StockReservationJpaEntity entity, StockReservation r) {
        entity.setStatus(r.status().name());
        entity.setUpdatedAt(r.updatedAt());
    }

    public StockReservation toDomain(StockReservationJpaEntity e) {
        return new StockReservation(
                e.getId(), e.getOrderId(), e.getProductId(), e.getQuantity(),
                ReservationStatus.valueOf(e.getStatus()), e.getExpiresAt(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
