package com.orderflow.order.adapter.out.persistence.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entity JPA của đơn hàng — CHỈ để map xuống database.
 *
 * <p>Đây là class SONG SONG với {@code domain.model.Order}, không phải bản sao
 * thừa. Nó tồn tại để Hibernate có chỗ áp đặt các yêu cầu của mình
 * (constructor rỗng, setter, quan hệ hai chiều, lazy proxy) mà không làm bẩn
 * domain model.
 *
 * <p>{@code @Version} bật optimistic locking: Hibernate thêm
 * {@code WHERE version = ?} vào mỗi UPDATE và tự tăng giá trị. Nếu có ai đó
 * sửa trước, UPDATE khớp 0 dòng và Hibernate ném
 * {@code OptimisticLockingFailureException} — thay vì lặng lẽ ghi đè mất
 * thay đổi của người kia (lost update).
 *
 * <p>Đây chính là countermeasure "reread value" của saga, xem
 * {@code docs/PATTERNS.md} mục 0.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // Hibernate cần, code khác không nên gọi
public class OrderJpaEntity {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * {@code cascade = ALL} + {@code orphanRemoval}: dòng hàng không sống độc lập
     * ngoài đơn, nên vòng đời của chúng gắn với đơn.
     *
     * <p>{@code FetchType.LAZY} là mặc định của {@code @OneToMany} và ta giữ nguyên;
     * adapter luôn nạp kèm bằng {@code JOIN FETCH} khi cần, tránh N+1 query.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItemJpaEntity> items = new ArrayList<>();

    public OrderJpaEntity(UUID id, UUID customerId, String status, BigDecimal totalAmount,
                          String currency, Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.customerId = customerId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Giữ hai đầu quan hệ đồng bộ — quên chiều ngược là lỗi JPA kinh điển. */
    public void addItem(OrderItemJpaEntity item) {
        items.add(item);
        item.setOrder(this);
    }
}
