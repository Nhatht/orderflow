package com.orderflow.order.adapter.out.persistence.mapper;

import com.orderflow.order.adapter.out.persistence.entity.OrderItemJpaEntity;
import com.orderflow.order.adapter.out.persistence.entity.OrderJpaEntity;
import com.orderflow.order.domain.model.Money;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.OrderStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Chuyển đổi giữa domain model và entity JPA.
 *
 * <p>Viết tay thay vì dùng MapStruct ở đây là có chủ đích: hai bên có cấu trúc
 * khác nhau thật sự (domain gom {@code amount + currency} thành {@link Money},
 * entity tách thành hai cột), nên code sinh tự động sẽ cần nhiều
 * {@code @Mapping} tuỳ biến đến mức không còn gọn hơn viết tay.
 *
 * <p>Đây cũng chính là "cái giá" của hexagonal mà ai cũng nhắc tới. Đổi lại,
 * domain model hoàn toàn không biết Hibernate tồn tại.
 */
@Component
public class OrderMapper {

    public OrderJpaEntity toEntity(Order order) {
        OrderJpaEntity entity = new OrderJpaEntity(
                order.id(),
                order.customerId(),
                order.status().name(),
                order.totalAmount().amount(),
                order.currency(),
                order.version(),
                order.createdAt(),
                order.updatedAt());

        order.items().forEach(item -> entity.addItem(toItemEntity(item)));
        return entity;
    }

    /**
     * Cập nhật entity đã có thay vì tạo mới.
     *
     * <p>Quan trọng: entity đang được Hibernate quản lý (managed). Tạo entity mới
     * cùng id rồi {@code save()} sẽ làm Hibernate nghĩ đây là bản detached,
     * gây merge thừa và có thể mất giá trị {@code @Version}.
     */
    public void updateEntity(OrderJpaEntity entity, Order order) {
        entity.setStatus(order.status().name());
        entity.setTotalAmount(order.totalAmount().amount());
        entity.setCurrency(order.currency());
        entity.setUpdatedAt(order.updatedAt());
    }

    public Order toDomain(OrderJpaEntity entity) {
        List<OrderItem> items = entity.getItems().stream()
                .map(this::toItemDomain)
                .toList();

        return new Order(
                entity.getId(),
                entity.getCustomerId(),
                OrderStatus.valueOf(entity.getStatus()),
                items,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion());
    }

    private OrderItemJpaEntity toItemEntity(OrderItem item) {
        return new OrderItemJpaEntity(
                item.id(),
                item.productId(),
                item.productName(),
                item.quantity(),
                item.unitPrice().amount(),
                item.unitPrice().currency());
    }

    private OrderItem toItemDomain(OrderItemJpaEntity entity) {
        return new OrderItem(
                entity.getId(),
                entity.getProductId(),
                entity.getProductName(),
                entity.getQuantity(),
                Money.of(entity.getUnitPrice(), entity.getCurrency()));
    }
}
