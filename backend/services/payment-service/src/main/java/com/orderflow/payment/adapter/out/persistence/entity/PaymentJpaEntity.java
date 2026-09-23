package com.orderflow.payment.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entity JPA của payment — chỉ để map xuống database, song song với
 * {@code domain.model.Payment}. Lý do tách: xem {@code OrderJpaEntity}.
 *
 * <p>{@code @Version}: hai lần giao cùng lúc cùng chờ cổng rồi cùng ghi kết quả
 * — bên ghi sau nhận {@code OptimisticLockingFailureException} thay vì ghi đè.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentJpaEntity {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "gateway_reference", length = 64)
    private String gatewayReference;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PaymentJpaEntity(UUID id, UUID orderId, UUID customerId, BigDecimal amount, String currency,
                            String status, String failureReason, String gatewayReference,
                            Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.failureReason = failureReason;
        this.gatewayReference = gatewayReference;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
