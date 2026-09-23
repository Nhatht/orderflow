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
 * <p><b>Lịch sử của dòng {@code publishOrderCreated} — ba phiên bản:</b>
 * <ol>
 *   <li><i>Gửi Kafka bên trong transaction</i> — commit fail sau khi Kafka đã
 *       nhận → inventory giữ hàng cho một đơn KHÔNG TỒN TẠI. Kafka không tham
 *       gia transaction của PostgreSQL, rollback không thu hồi được message.</li>
 *   <li><i>Gửi Kafka sau commit</i> (tuần 4) — không còn bịa event, nhưng app
 *       chết giữa commit và gửi thì MẤT event: đơn kẹt PENDING, không log lỗi.
 *       Đã tái hiện thật ngày 23/09/2026: tắt Kafka, đặt đơn, kill app.</li>
 *   <li><i>Ghi vào bảng outbox bên trong transaction</i> (tuần 5, hiện tại) —
 *       đơn và event cùng một database, cùng commit hoặc cùng rollback.
 *       {@code OutboxPoller} đẩy lên Kafka sau. Luồng HTTP không chạm tới
 *       Kafka nữa: Kafka sập thì đặt đơn vẫn chạy bình thường.</li>
 * </ol>
 *
 * <p>Dùng {@link TransactionTemplate} thay {@code @Transactional} để ranh giới
 * transaction hiện rõ ngay trong code: đọc là thấy publish nằm TRONG.
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

        Order saved = tx.execute(status -> {
            Order persisted = orderRepository.save(order);
            eventPublisher.publishOrderCreated(persisted);   // ← CÙNG transaction: ghi outbox
            return persisted;
        });                                                  // ← COMMIT cả đơn lẫn event

        log.info("Order placed: id={}, customer={}, total={}",
                saved.id(), saved.customerId(), saved.totalAmount());

        return OrderView.from(saved);
    }
}
