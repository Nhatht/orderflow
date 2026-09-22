package com.orderflow.order.application.service;

import com.orderflow.order.application.dto.OrderView;
import com.orderflow.order.application.dto.PlaceOrderCommand;
import com.orderflow.order.application.port.in.PlaceOrderUseCase;
import com.orderflow.order.application.port.out.OrderRepositoryPort;
import com.orderflow.order.domain.model.Money;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Use case đặt hàng.
 *
 * <p>Tầng application ĐIỀU PHỐI, không chứa luật nghiệp vụ. Luật nằm trong
 * domain model: {@code OrderItem} tự kiểm tra quantity dương, {@code Money}
 * tự kiểm tra currency, {@code Order} tự kiểm tra luật chuyển trạng thái.
 * Ở đây chỉ dịch command thành domain object, gọi domain, rồi lưu lại.
 *
 * <p>Đây là ranh giới rõ nhất giữa "service béo, model rỗng" (anemic domain —
 * thứ cần tránh) và cách làm này.
 *
 * <p><b>Tuần 6 sẽ bổ sung:</b> sau khi lưu đơn, ghi thêm một bản ghi vào bảng
 * {@code outbox} TRONG CÙNG transaction này, để phát event {@code OrderCreated}.
 * Xem {@code docs/PATTERNS.md} mục 2.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceOrderService implements PlaceOrderUseCase {

    private final OrderRepositoryPort orderRepository;

    @Override
    @Transactional
    public OrderView placeOrder(PlaceOrderCommand command) {
        List<OrderItem> items = command.items().stream()
                .map(i -> OrderItem.create(
                        i.productId(),
                        i.productName(),
                        i.quantity(),
                        Money.of(i.unitPrice(), command.currency())))
                .toList();

        Order order = Order.place(command.customerId(), items);
        Order saved = orderRepository.save(order);

        log.info("Order placed: id={}, customer={}, total={}",
                saved.id(), saved.customerId(), saved.totalAmount());

        return OrderView.from(saved);
    }
}
