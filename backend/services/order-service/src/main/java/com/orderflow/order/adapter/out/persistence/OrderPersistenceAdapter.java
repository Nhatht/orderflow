package com.orderflow.order.adapter.out.persistence;

import com.orderflow.order.adapter.out.persistence.entity.OrderJpaEntity;
import com.orderflow.order.adapter.out.persistence.mapper.OrderMapper;
import com.orderflow.order.adapter.out.persistence.repository.OrderJpaRepository;
import com.orderflow.order.application.port.out.OrderRepositoryPort;
import com.orderflow.order.domain.model.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cài đặt {@link OrderRepositoryPort} bằng JPA.
 *
 * <p>Đây là chỗ duy nhất trong luồng lưu trữ biết đến Hibernate. Nó nhận vào
 * domain model, chuyển sang entity, gọi Spring Data, rồi chuyển ngược lại.
 *
 * <p>Đổi sang MongoDB hay lưu ra file chỉ cần viết một adapter khác cài cùng
 * interface — tầng application không sửa dòng nào.
 */
@Component
@RequiredArgsConstructor
public class OrderPersistenceAdapter implements OrderRepositoryPort {

    private final OrderJpaRepository repository;
    private final OrderMapper mapper;

    @Override
    public Order save(Order order) {
        // Đơn đã tồn tại thì cập nhật entity đang được quản lý, không tạo mới.
        // Xem ghi chú ở OrderMapper.updateEntity().
        OrderJpaEntity entity = repository.findByIdWithItems(order.id())
                .map(existing -> {
                    mapper.updateEntity(existing, order);
                    return existing;
                })
                .orElseGet(() -> mapper.toEntity(order));

        OrderJpaEntity saved = repository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return repository.findByIdWithItems(orderId).map(mapper::toDomain);
    }

    @Override
    public List<Order> findByCustomerId(UUID customerId) {
        return repository.findByCustomerIdWithItems(customerId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public boolean existsById(UUID orderId) {
        return repository.existsById(orderId);
    }
}
