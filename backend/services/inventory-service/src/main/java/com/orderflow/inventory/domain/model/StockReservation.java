package com.orderflow.inventory.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phiếu giữ hàng — bằng chứng rằng đơn {@code orderId} đang giữ
 * {@code quantity} sản phẩm.
 *
 * <p>Đây là hiện thân của <b>semantic lock</b>: thay vì khoá hàng bằng cơ chế
 * kỹ thuật (row lock giữ suốt giao dịch, như 2PC làm), ta ghi một bản ghi
 * nghiệp vụ nói rõ "hàng này đang được giữ, tới thời điểm này thì hết hạn".
 *
 * <p>Khác biệt then chốt so với row lock: phiếu này KHÔNG chặn ai cả. Database
 * không bị giữ khoá, service khác vẫn đọc ghi bình thường. Nếu service giữ
 * phiếu chết giữa chừng, phiếu tự hết hạn và job dọn dẹp nhả hàng —
 * không có gì treo vô hạn như coordinator của 2PC.
 */
public class StockReservation {

    private final UUID id;
    private final UUID orderId;
    private final UUID productId;
    private final int quantity;
    private final Instant createdAt;
    private final Instant expiresAt;

    private ReservationStatus status;
    private Instant updatedAt;

    public StockReservation(UUID id, UUID orderId, UUID productId, int quantity,
                            ReservationStatus status, Instant expiresAt,
                            Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got: " + quantity);
        }
        this.quantity = quantity;
    }

    /**
     * Tạo phiếu giữ hàng mới.
     *
     * @param ttl thời gian giữ. Đặt theo p99 của bước thanh toán, KHÔNG phải
     *            theo mong muốn — quá ngắn sẽ huỷ nhầm đơn hợp lệ khi khách
     *            còn đang nhập OTP. Xem docs/PATTERNS.md, bẫy "late response".
     */
    public static StockReservation hold(UUID orderId, UUID productId, int quantity, Duration ttl) {
        Instant now = Instant.now();
        return new StockReservation(
                UUID.randomUUID(), orderId, productId, quantity,
                ReservationStatus.HELD, now.plus(ttl), now, now);
    }

    /** Đơn chốt thành công — hàng rời kho. */
    public void confirm() {
        requireActive("confirm");
        this.status = ReservationStatus.CONFIRMED;
        touch();
    }

    /** Đơn huỷ — nhả hàng về kho. Đây là bước compensate. */
    public void release() {
        requireActive("release");
        this.status = ReservationStatus.RELEASED;
        touch();
    }

    /** Quá hạn — job dọn dẹp gọi. */
    public void expire() {
        requireActive("expire");
        this.status = ReservationStatus.EXPIRED;
        touch();
    }

    /**
     * Đơn được chốt SAU KHI phiếu đã hết hạn — cuộc đua giữa job hết hạn và
     * {@code order.confirmed}. Hàng đã được lấy lại từ kho (xem
     * {@link Stock#confirmFromAvailable(int)}) nên phiếu chuyển sang CONFIRMED
     * như bình thường. Chỉ đi được từ EXPIRED — RELEASED nghĩa là saga đã huỷ
     * đơn, không có chuyện chốt lại.
     */
    public void confirmAfterExpiry() {
        if (status != ReservationStatus.EXPIRED) {
            throw new IllegalStateException(
                    "Cannot confirm-after-expiry reservation %s: status is %s".formatted(id, status));
        }
        this.status = ReservationStatus.CONFIRMED;
        touch();
    }

    public boolean isExpired(Instant now) {
        return status.isActive() && now.isAfter(expiresAt);
    }

    /**
     * Phiếu đã ở trạng thái cuối thì không đổi được nữa.
     *
     * <p>Chặn đúng tình huống late response: phiếu đã EXPIRED và hàng đã nhả,
     * rồi {@code PaymentCompleted} của cổng thanh toán mới về tới và đòi
     * confirm. Ném lỗi ở đây để saga biết mà đi nhánh hoàn tiền.
     */
    private void requireActive(String action) {
        if (status.isFinal()) {
            throw new IllegalStateException(
                    "Cannot %s reservation %s: already %s".formatted(action, id, status));
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID id()                    { return id; }
    public UUID orderId()               { return orderId; }
    public UUID productId()             { return productId; }
    public int quantity()               { return quantity; }
    public ReservationStatus status()   { return status; }
    public Instant expiresAt()          { return expiresAt; }
    public Instant createdAt()          { return createdAt; }
    public Instant updatedAt()          { return updatedAt; }

    @Override
    public String toString() {
        return "StockReservation[id=%s, order=%s, product=%s, qty=%d, status=%s, expires=%s]"
                .formatted(id, orderId, productId, quantity, status, expiresAt);
    }
}
