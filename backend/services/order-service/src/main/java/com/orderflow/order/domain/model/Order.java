package com.orderflow.order.domain.model;

import com.orderflow.order.domain.exception.InvalidOrderStateException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root của đơn hàng.
 *
 * <p><b>Đây là domain model, KHÔNG phải entity JPA.</b> Không có {@code @Entity},
 * không có {@code @Column}, không import gì của Spring hay Hibernate. Việc map
 * xuống database là chuyện của {@code adapter/out/persistence}.
 *
 * <p>Vì sao tách ra thay vì dùng thẳng entity JPA:
 * <ul>
 *   <li>Hibernate đòi constructor rỗng và setter cho mọi field — phá vỡ tính bất
 *       biến và cho phép đặt object vào trạng thái không hợp lệ.</li>
 *   <li>Lazy loading proxy rò rỉ ra tầng nghiệp vụ, gây {@code LazyInitializationException}
 *       ở những chỗ không ngờ tới.</li>
 *   <li>Class này test được bằng unit test thuần, không cần bật Spring context —
 *       chạy mất mili giây thay vì vài giây.</li>
 * </ul>
 *
 * <p>Đây cũng KHÔNG phải anemic model: mọi luật nghiệp vụ (chuyển trạng thái,
 * tính tổng tiền) nằm ngay trong class này, không nằm rải rác ở tầng service.
 */
public class Order {

    private final UUID id;
    private final UUID customerId;
    private final List<OrderItem> items;
    private final Instant createdAt;

    private OrderStatus status;
    private Instant updatedAt;
    private Long version;

    /** Dùng khi đọc từ database lên — khôi phục nguyên trạng thái đã lưu. */
    public Order(UUID id, UUID customerId, OrderStatus status, List<OrderItem> items,
                 Instant createdAt, Instant updatedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;

        Objects.requireNonNull(items, "items must not be null");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item");
        }
        this.items = List.copyOf(items);   // bản sao bất biến, người gọi không sửa được từ ngoài
    }

    /**
     * Factory tạo đơn mới. Đơn luôn bắt đầu ở {@link OrderStatus#PENDING} —
     * chưa giữ hàng, chưa thu tiền. Saga sẽ đẩy nó đi tiếp từ đây.
     */
    public static Order place(UUID customerId, List<OrderItem> items) {
        Instant now = Instant.now();
        return new Order(UUID.randomUUID(), customerId, OrderStatus.PENDING,
                items, now, now, null);
    }

    /**
     * Tổng tiền, tính từ các dòng hàng chứ không lưu sẵn.
     *
     * <p>Cột {@code total_amount} dưới database chỉ là bản sao đã tính, phục vụ
     * truy vấn và báo cáo. Nguồn sự thật luôn là các dòng hàng — nhờ vậy
     * tổng tiền không bao giờ lệch khỏi chi tiết đơn.
     */
    public Money totalAmount() {
        return items.stream()
                .map(OrderItem::subtotal)
                .reduce(Money::add)
                .orElseThrow(() -> new IllegalStateException("Order has no items"));
    }

    public String currency() {
        return items.getFirst().unitPrice().currency();
    }

    // ---- Chuyển trạng thái (saga sẽ gọi từ tuần 6) --------------------------

    public void markStockReserved() {
        transitionTo(OrderStatus.STOCK_RESERVED);
    }

    public void markPaid() {
        transitionTo(OrderStatus.PAID);
    }

    public void confirm() {
        transitionTo(OrderStatus.CONFIRMED);
    }

    public void cancel() {
        transitionTo(OrderStatus.CANCELLED);
    }

    private void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidOrderStateException(id, status, target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    // ---- Getter (không có setter: trạng thái chỉ đổi qua hành vi ở trên) -----

    public UUID id()                { return id; }
    public UUID customerId()        { return customerId; }
    public OrderStatus status()     { return status; }
    public List<OrderItem> items()  { return items; }
    public Instant createdAt()      { return createdAt; }
    public Instant updatedAt()      { return updatedAt; }
    public Long version()           { return version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order other)) return false;
        return id.equals(other.id);   // aggregate so sánh bằng identity, không phải giá trị
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Order[id=%s, status=%s, items=%d, total=%s]"
                .formatted(id, status, items.size(), totalAmount());
    }
}
