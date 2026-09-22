package com.orderflow.inventory.adapter.out.persistence.repository;

import com.orderflow.inventory.adapter.out.persistence.entity.StockJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StockJpaRepository extends JpaRepository<StockJpaEntity, UUID> {

    List<StockJpaEntity> findByProductIdIn(List<UUID> productIds);
}
