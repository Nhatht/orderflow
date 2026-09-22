package com.orderflow.order.adapter.out.persistence.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** Entity JPA của dòng hàng. Xem ghi chú ở {@link OrderJpaEntity}. */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemJpaEntity {

    @Id
    private UUID id;

    /**
     * {@code FetchType.LAZY} ở đây là bắt buộc phải khai rõ: mặc định của
     * {@code @ManyToOne} là EAGER, nghĩa là nạp một dòng hàng sẽ kéo theo cả
     * đơn hàng — nguồn gốc của vô số truy vấn thừa.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OrderJpaEntity order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, length = 3)
    private String currency;

    public OrderItemJpaEntity(UUID id, UUID productId, String productName,
                              int quantity, BigDecimal unitPrice, String currency) {
        this.id = id;
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.currency = currency;
    }
}
