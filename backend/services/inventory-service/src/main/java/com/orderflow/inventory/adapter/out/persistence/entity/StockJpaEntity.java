package com.orderflow.inventory.adapter.out.persistence.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity JPA của tồn kho.
 *
 * <p>{@code @Version} ở đây là LỚP BẢO VỆ THỨ HAI sau Redis lock. Hibernate
 * thêm {@code WHERE version = ?} vào mỗi UPDATE; nếu có ai sửa trước thì
 * UPDATE khớp 0 dòng và ném OptimisticLockingFailureException.
 *
 * <p>Vì sao cần khi đã có Redis lock: lease của khoá có thể hết hạn giữa chừng,
 * Redis có thể mất kết nối, hoặc ai đó viết code mới quên khoá. Lớp này bắt
 * được tất cả những trường hợp đó.
 */
@Entity
@Table(name = "stock")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockJpaEntity {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    @Column(name = "reserved_qty", nullable = false)
    private int reservedQty;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public StockJpaEntity(UUID productId, int availableQty, int reservedQty,
                          Long version, Instant updatedAt) {
        this.productId = productId;
        this.availableQty = availableQty;
        this.reservedQty = reservedQty;
        this.version = version;
        this.updatedAt = updatedAt;
    }
}
