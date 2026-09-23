package com.orderflow.inventory.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Kết quả giữ hàng cho một đơn — đúng BA khả năng, không hơn.
 *
 * <p>Dùng {@code sealed interface} thay vì ném exception cho trường hợp hết
 * hàng: hết hàng là kết quả nghiệp vụ bình thường, không phải sự cố. Người gọi
 * {@code switch} trên kết quả và trình biên dịch BẮT BUỘC xử lý đủ cả ba
 * nhánh — quên nhánh {@link Duplicate} là lỗi biên dịch, không phải bug lúc chạy.
 *
 * <p>Exception để dành cho lỗi thật sự (database sập, không lấy được khoá):
 * những lỗi đó khiến consumer ném ra để Kafka giao lại message sau.
 */
public sealed interface ReservationOutcome {

    UUID orderId();

    /** Giữ được hàng cho mọi dòng trong đơn. */
    record Reserved(UUID orderId, List<ReservationView> reservations, Instant expiresAt)
            implements ReservationOutcome {}

    /** Không giữ được — không dòng nào bị giữ (tất cả hoặc không). */
    record Rejected(UUID orderId, Reason reason, List<Shortage> shortages)
            implements ReservationOutcome {

        public enum Reason { INSUFFICIENT_STOCK, UNKNOWN_PRODUCT }

        /** {@code available} = 0 nếu sản phẩm không tồn tại. */
        public record Shortage(UUID productId, int requested, int available) {}
    }

    /**
     * Lệnh này đã được xử lý trước đó (cùng idempotency key) — không làm gì cả.
     *
     * <p>Cố ý KHÔNG mang theo kết quả của lần đầu: kết quả đó đã được phát đi
     * rồi. Phát lại thì order-service nhận hai lần {@code stock.reserved} cho
     * cùng một đơn.
     */
    record Duplicate(UUID orderId) implements ReservationOutcome {}
}
