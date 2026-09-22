package com.orderflow.order.application.service;

import com.orderflow.order.application.dto.OrderView;
import com.orderflow.order.application.port.in.GetOrderQuery;
import com.orderflow.order.application.port.out.OrderRepositoryPort;
import com.orderflow.order.domain.exception.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Use case đọc đơn hàng.
 *
 * <p>{@code readOnly = true} không chỉ là chú thích cho người đọc: Hibernate
 * bỏ qua dirty checking ở transaction read-only, và driver có thể định tuyến
 * sang read replica. Với truy vấn nhiều, khác biệt là đáng kể.
 */
@Service
@RequiredArgsConstructor
public class GetOrderService implements GetOrderQuery {

    private final OrderRepositoryPort orderRepository;

    @Override
    @Transactional(readOnly = true)
    public OrderView getById(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(OrderView::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderView> getByCustomer(UUID customerId) {
        return orderRepository.findByCustomerId(customerId).stream()
                .map(OrderView::from)
                .toList();
    }
}
