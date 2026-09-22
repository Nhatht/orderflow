package com.orderflow.order.adapter.out.persistence.repository;

import com.orderflow.order.adapter.out.persistence.entity.OrderJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository — CHI TIẾT HẠ TẦNG, không phải port.
 *
 * <p>Interface này nằm ở tầng adapter và chỉ được
 * {@code OrderPersistenceAdapter} gọi. Tầng application không biết nó tồn tại;
 * application chỉ biết {@code OrderRepositoryPort}.
 */
public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, UUID> {

    /**
     * {@code JOIN FETCH} nạp luôn dòng hàng trong CÙNG MỘT truy vấn.
     *
     * <p>Không có nó thì mỗi lần chạm vào {@code getItems()} sẽ sinh thêm một
     * câu SELECT — đọc 100 đơn thành 101 truy vấn (N+1 problem). Đây là câu hỏi
     * phỏng vấn gần như chắc chắn được hỏi khi nói tới JPA.
     */
    @Query("SELECT DISTINCT o FROM OrderJpaEntity o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<OrderJpaEntity> findByIdWithItems(@Param("id") UUID id);

    @Query("SELECT DISTINCT o FROM OrderJpaEntity o LEFT JOIN FETCH o.items "
         + "WHERE o.customerId = :customerId ORDER BY o.createdAt DESC")
    List<OrderJpaEntity> findByCustomerIdWithItems(@Param("customerId") UUID customerId);
}
