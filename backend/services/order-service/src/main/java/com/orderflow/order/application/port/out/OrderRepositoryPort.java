package com.orderflow.order.application.port.out;

import com.orderflow.order.domain.model.Order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CỔNG RA (driven port) — mô tả hệ thống CẦN GÌ từ bên ngoài.
 *
 * <p>Đây là chỗ đảo ngược phụ thuộc quan trọng nhất của hexagonal:
 * interface này được ĐỊNH NGHĨA ở tầng application, nhưng được CÀI ĐẶT
 * ở tầng adapter. Nhờ vậy application không phụ thuộc vào JPA —
 * nó chỉ phụ thuộc vào interface do chính nó đặt ra.
 *
 * <p>Đổi từ PostgreSQL sang MongoDB chỉ cần viết adapter mới, tầng
 * application không sửa một dòng nào.
 *
 * <p>Chú ý chữ ký hàm: nhận và trả về {@link Order} (domain model),
 * KHÔNG phải entity JPA. Việc chuyển đổi là chuyện nội bộ của adapter.
 */
public interface OrderRepositoryPort {

    Order save(Order order);

    Optional<Order> findById(UUID orderId);

    List<Order> findByCustomerId(UUID customerId);

    boolean existsById(UUID orderId);
}
