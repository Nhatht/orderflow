package com.orderflow.payment.adapter.out.persistence;

import com.orderflow.payment.adapter.out.persistence.entity.PaymentJpaEntity;
import com.orderflow.payment.adapter.out.persistence.repository.PaymentJpaRepository;
import com.orderflow.payment.application.port.out.PaymentRepositoryPort;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Cài đặt {@link PaymentRepositoryPort} bằng JPA.
 *
 * <p>Mapper viết tay ngay trong adapter: chỉ một aggregate, một bảng, không
 * có quan hệ con — tách thêm class mapper riêng như order-service là thừa.
 */
@Component
@RequiredArgsConstructor
public class PaymentPersistenceAdapter implements PaymentRepositoryPort {

    private final PaymentJpaRepository repository;

    @Override
    public Payment save(Payment payment) {
        PaymentJpaEntity entity = repository.findById(payment.id())
                .map(existing -> {
                    // Chỉ những field THẬT SỰ đổi được sau khi tạo. Số tiền,
                    // đơn, khách hàng là bất biến — không có setter nào chạm tới.
                    existing.setStatus(payment.status().name());
                    existing.setFailureReason(payment.failureReason());
                    existing.setGatewayReference(payment.gatewayReference());
                    existing.setUpdatedAt(payment.updatedAt());
                    return existing;
                })
                .orElseGet(() -> new PaymentJpaEntity(
                        payment.id(), payment.orderId(), payment.customerId(), payment.amount(),
                        payment.currency(), payment.status().name(), payment.failureReason(),
                        payment.gatewayReference(), payment.version(), payment.createdAt(), payment.updatedAt()));

        return toDomain(repository.save(entity));
    }

    @Override
    public Optional<Payment> findByOrderId(UUID orderId) {
        return repository.findByOrderId(orderId).map(PaymentPersistenceAdapter::toDomain);
    }

    private static Payment toDomain(PaymentJpaEntity e) {
        return new Payment(e.getId(), e.getOrderId(), e.getCustomerId(), e.getAmount(), e.getCurrency(),
                PaymentStatus.valueOf(e.getStatus()), e.getFailureReason(), e.getGatewayReference(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
    }
}
