package com.orderflow.order.domain;

import com.orderflow.order.domain.exception.InvalidOrderStateException;
import com.orderflow.order.domain.model.Money;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit test thuần cho domain — KHÔNG bật Spring context, KHÔNG chạm database.
 *
 * <p>Cả file này chạy xong trong vài mili giây. Đó chính là phần thưởng của
 * việc tách domain khỏi hạ tầng: luật nghiệp vụ kiểm chứng được mà không cần
 * dựng bất cứ thứ gì.
 */
class OrderTest {

    private static OrderItem item(String price, int qty) {
        return OrderItem.create(UUID.randomUUID(), "Test product", qty, Money.of(price, "VND"));
    }

    @Nested
    @DisplayName("Money")
    class MoneyTest {

        @Test
        @DisplayName("chuẩn hoá scale nên 10.5 bằng 10.5000")
        void normalisesScale() {
            assertThat(Money.of("10.5", "VND")).isEqualTo(Money.of("10.5000", "VND"));
        }

        @Test
        @DisplayName("cộng khác currency thì ném lỗi")
        void rejectsCurrencyMismatch() {
            assertThatThrownBy(() -> Money.of("10", "VND").add(Money.of("10", "USD")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Currency mismatch");
        }

        @Test
        @DisplayName("cộng chính xác, không có sai số dấu phẩy động")
        void addsExactly() {
            Money sum = Money.of("0.1", "VND").add(Money.of("0.2", "VND"));
            assertThat(sum.amount()).isEqualByComparingTo(new BigDecimal("0.3"));
        }

        @Test
        @DisplayName("currency phải đúng 3 ký tự")
        void rejectsInvalidCurrency() {
            assertThatThrownBy(() -> Money.of("10", "VNDX"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Tạo đơn")
    class Placing {

        @Test
        @DisplayName("đơn mới luôn ở trạng thái PENDING")
        void startsPending() {
            Order order = Order.place(UUID.randomUUID(), List.of(item("100", 1)));
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        }

        @Test
        @DisplayName("tổng tiền tính từ các dòng hàng")
        void calculatesTotal() {
            Order order = Order.place(UUID.randomUUID(),
                    List.of(item("100.50", 2), item("49.00", 1)));

            // 100.50 * 2 + 49.00 = 250.00
            assertThat(order.totalAmount().amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        }

        @Test
        @DisplayName("đơn rỗng bị từ chối")
        void rejectsEmptyOrder() {
            UUID customerId = UUID.randomUUID();
            assertThatThrownBy(() -> Order.place(customerId, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one item");
        }

        @Test
        @DisplayName("quantity không dương bị từ chối")
        void rejectsNonPositiveQuantity() {
            assertThatThrownBy(() -> item("100", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quantity must be positive");
        }

        @Test
        @DisplayName("danh sách items trả ra là bất biến")
        void itemsAreImmutable() {
            Order order = Order.place(UUID.randomUUID(), List.of(item("100", 1)));
            List<OrderItem> items = order.items();
            OrderItem extra = item("1", 1);

            assertThatThrownBy(() -> items.add(extra))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Chuyển trạng thái")
    class Transitions {

        private Order newOrder() {
            return Order.place(UUID.randomUUID(), List.of(item("100", 1)));
        }

        @Test
        @DisplayName("đi hết luồng thuận PENDING → CONFIRMED")
        void happyPath() {
            Order order = newOrder();

            order.markStockReserved();
            assertThat(order.status()).isEqualTo(OrderStatus.STOCK_RESERVED);

            order.markPaid();
            assertThat(order.status()).isEqualTo(OrderStatus.PAID);

            order.confirm();
            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @Test
        @DisplayName("huỷ được từ bất kỳ trạng thái nào chưa kết thúc")
        void cancellableBeforeFinal() {
            Order fromPending = newOrder();
            fromPending.cancel();
            assertThat(fromPending.status()).isEqualTo(OrderStatus.CANCELLED);

            Order fromReserved = newOrder();
            fromReserved.markStockReserved();
            fromReserved.cancel();
            assertThat(fromReserved.status()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("không nhảy cóc PENDING → CONFIRMED")
        void rejectsSkippingSteps() {
            Order order = newOrder();
            assertThatThrownBy(order::confirm)
                    .isInstanceOf(InvalidOrderStateException.class)
                    .hasMessageContaining("cannot transition from PENDING to CONFIRMED");
        }

        @Test
        @DisplayName("đơn đã huỷ không nhận được event đến muộn — late response")
        void rejectsTransitionFromFinalState() {
            Order order = newOrder();
            order.cancel();

            // Tình huống có thật: thanh toán timeout, saga đã compensate,
            // rồi PaymentCompleted của cổng thanh toán mới về tới.
            assertThatThrownBy(order::markPaid)
                    .isInstanceOf(InvalidOrderStateException.class);
        }

        @Test
        @DisplayName("CONFIRMED và CANCELLED là trạng thái cuối")
        void finalStatesAreFinal() {
            assertThat(OrderStatus.CONFIRMED.isFinal()).isTrue();
            assertThat(OrderStatus.CANCELLED.isFinal()).isTrue();
            assertThat(OrderStatus.PENDING.isFinal()).isFalse();
        }
    }
}
