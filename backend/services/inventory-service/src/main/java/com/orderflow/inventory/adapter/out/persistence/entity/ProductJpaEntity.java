package com.orderflow.inventory.adapter.out.persistence.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Bảng {@code products} (có từ V1, tuần 3). Chỉ ĐỌC — dự án không có API sửa
 * sản phẩm; dữ liệu đến từ seed Flyway.
 *
 * <p>{@code @Immutable}: Hibernate không bao giờ sinh UPDATE cho entity này và
 * bỏ qua dirty checking (rẻ hơn khi đọc).
 */
@Entity
@Table(name = "products")
@org.hibernate.annotations.Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency;
}
