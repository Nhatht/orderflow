package com.orderflow.order.application.service;

import com.orderflow.order.application.dto.OrderView;
import com.orderflow.order.application.dto.PlaceOrderCommand;
import com.orderflow.order.application.port.in.PlaceOrderUseCase;
import com.orderflow.order.application.port.out.EventPublisherPort;
import com.orderflow.order.application.port.out.OrderRepositoryPort;
import com.orderflow.order.domain.model.Money;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p><b>Vì sao phát event SAU khi commit, không phải bên trong transaction:</b>
 * <pre>
 *   @Transactional
 *   placeOrder() {
 *       save(order);
 *       publish(event);     // Kafka nhận event ngay...
 *   }                       // ...rồi commit FAIL → rollback
 * </pre>
 * Inventory nhận {@code order.created} cho một đơn KHÔNG TỒN TẠI, giữ hàng cho
 * một bóng ma. Kafka không tham gia transaction của PostgreSQL, nên rollback
 * không thu hồi được message đã gửi.
 *
 * <p>Đổi thứ tự thành "commit xong mới phát" thì lỗi tệ nhất chỉ còn là
 * <i>mất</i> event (đơn kẹt ở PENDING), chứ không bao giờ <i>bịa</i> ra event
 * cho đơn không có thật. Vẫn chưa đúng hoàn toàn — đó là việc của
 * Transactional Outbox ở tuần 5 — nhưng là lỗi dễ phát hiện và sửa hơn.
 *
 * <p>Dùng {@link TransactionTemplate} thay {@code @Transactional} để ranh giới
 * transaction hiện rõ ngay trong code: đọc là thấy publish nằm NGOÀI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceOrderService implements PlaceOrderUseCase {

    private final OrderRepositoryPort orderRepository;
    private final EventPublisherPort eventPublisher;
    private final TransactionTemplate tx;

    @Override
    public OrderView placeOrder(PlaceOrderCommand command) {
        List<OrderItem> items = command.items().stream()
                .map(i -> OrderItem.create(
                        i.productId(),
                        i.productName(),
                        i.quantity(),
                        Money.of(i.unitPrice(), command.currency())))
                .toList();

        Order order = Order.place(command.customerId(), items);

        Order saved = tx.execute(status -> orderRepository.save(order));   // ← COMMIT ở đây

        log.info("Order placed: id={}, customer={}, total={}",
                saved.id(), saved.customerId(), saved.totalAmount());

        eventPublisher.publishOrderCreated(saved);                         // ← sau commit

        return OrderView.from(saved);
    }
}
