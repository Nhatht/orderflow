package com.orderflow.inventory.adapter.out.persistence.repository;

import com.orderflow.inventory.adapter.out.persistence.entity.ProductJpaEntity;
import com.orderflow.inventory.application.dto.ProductView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, UUID> {

    /**
     * MỘT câu SQL cho cả danh sách — không N+1 (đọc sản phẩm rồi gọi tồn kho từng cái).
     *
     * <p>Hai entity không có quan hệ {@code @OneToOne} nên dùng "ad-hoc join" của
     * Hibernate 6 ({@code JOIN ... ON}). LEFT JOIN + COALESCE: sản phẩm chưa có dòng
     * tồn kho vẫn hiện, với số lượng 0, thay vì biến mất khỏi catalog.
     *
     * <p>Constructor expression tạo thẳng read model — không nạp entity vào
     * persistence context, không dirty checking.
     */
    @Query("""
            SELECT new com.orderflow.inventory.application.dto.ProductView(
                       p.id, p.sku, p.name, p.price, p.currency, COALESCE(s.availableQty, 0))
            FROM ProductJpaEntity p
            LEFT JOIN StockJpaEntity s ON s.productId = p.id
            ORDER BY p.sku
            """)
    List<ProductView> findAllWithAvailability();
}
