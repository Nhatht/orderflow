package com.orderflow.inventory.domain.model;

import com.orderflow.inventory.domain.exception.InsufficientStockException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Tồn kho của một sản phẩm — aggregate root.
 *
 * <p><b>Bất biến cốt lõi của class này:</b> {@code availableQty} và
 * {@code reservedQty} không bao giờ âm, và tổng của chúng chỉ đổi khi hàng
 * thật sự vào kho (nhập hàng) hoặc rời kho (giao hàng). Giữ hàng chỉ là
 * CHUYỂN giữa hai cột, không làm tổng thay đổi.
 *
 * <pre>
 *   Ban đầu:         available = 1,  reserved = 0    (tổng 1)
 *   reserve(1):      available = 0,  reserved = 1    (tổng 1)  ← hàng vẫn còn
 *   release(1):      available = 1,  reserved = 0    (tổng 1)  ← compensate
 *   confirm(1):      available = 0,  reserved = 0    (tổng 0)  ← hàng đã đi
 * </pre>
 *
 * <p>Đây là domain thuần: không Spring, không JPA, không Redis. Logic chống
 * bán quá số lượng nằm ở {@link #reserve(int)} và test được mà không cần
 * dựng bất cứ hạ tầng nào.
 */
public class Stock {

    private final UUID productId;
    private int availableQty;
    private int reservedQty;
    private Instant updatedAt;
    private Long version;

    public Stock(UUID productId, int availableQty, int reservedQty, Instant updatedAt, Long version) {
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        if (availableQty < 0) {
            throw new IllegalArgumentException("availableQty must not be negative: " + availableQty);
        }
        if (reservedQty < 0) {
            throw new IllegalArgumentException("reservedQty must not be negative: " + reservedQty);
        }
        this.availableQty = availableQty;
        this.reservedQty = reservedQty;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    /**
     * Giữ hàng cho một đơn.
     *
     * <p>ĐÂY LÀ CHỖ CHỐNG OVERSELL. Kiểm tra {@code availableQty >= quantity}
     * trông đơn giản, nhưng nó chỉ đúng khi được gọi trong vùng đã khoá —
     * nếu không, 100 luồng cùng đọc {@code availableQty = 1} và cả 100 cùng
     * thấy đủ hàng.
     *
     * <p>Vì vậy phải có ba lớp bảo vệ chồng lên nhau:
     * <ol>
     *   <li>Redis distributed lock — chỉ một luồng vào được tại một thời điểm</li>
     *   <li>{@code @Version} optimistic lock — chặn lost update ở tầng DB</li>
     *   <li>CHECK constraint {@code available_qty >= 0} — chốt chặn cuối cùng</li>
     * </ol>
     *
     * @throws InsufficientStockException khi không đủ hàng
     */
    public void reserve(int quantity) {
        requirePositive(quantity);
        if (availableQty < quantity) {
            throw new InsufficientStockException(productId, quantity, availableQty);
        }
        availableQty -= quantity;
        reservedQty += quantity;
        touch();
    }

    /**
     * Nhả hàng đã giữ về lại kho — ĐÂY LÀ HÀNH ĐỘNG ĐỀN BÙ (compensate) của saga.
     *
     * <p>Gọi khi thanh toán thất bại, đơn bị huỷ, hoặc phiếu giữ hàng quá hạn.
     * Lưu ý đây không phải "rollback": bản ghi vẫn còn, chỉ là trạng thái
     * chuyển sang RELEASED và số lượng quay về cột available.
     */
    public void release(int quantity) {
        requirePositive(quantity);
        if (reservedQty < quantity) {
            throw new IllegalStateException(
                    "Cannot release %d, only %d reserved for product %s"
                            .formatted(quantity, reservedQty, productId));
        }
        reservedQty -= quantity;
        availableQty += quantity;
        touch();
    }

    /**
     * Chốt: hàng đã giữ nay thật sự rời kho.
     *
     * <p>Chỉ trừ {@code reservedQty}, KHÔNG cộng lại vào {@code availableQty} —
     * đây là lúc duy nhất tổng tồn kho giảm đi.
     */
    public void confirm(int quantity) {
        requirePositive(quantity);
        if (reservedQty < quantity) {
            throw new IllegalStateException(
                    "Cannot confirm %d, only %d reserved for product %s"
                            .formatted(quantity, reservedQty, productId));
        }
        reservedQty -= quantity;
        touch();
    }

    /** Nhập thêm hàng vào kho. */
    public void restock(int quantity) {
        requirePositive(quantity);
        availableQty += quantity;
        touch();
    }

    /** Tổng hàng còn trong kho, kể cả phần đang bị giữ. */
    public int totalQty() {
        return availableQty + reservedQty;
    }

    public boolean canReserve(int quantity) {
        return quantity > 0 && availableQty >= quantity;
    }

    private void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got: " + quantity);
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID productId()     { return productId; }
    public int availableQty()   { return availableQty; }
    public int reservedQty()    { return reservedQty; }
    public Instant updatedAt()  { return updatedAt; }
    public Long version()       { return version; }

    @Override
    public String toString() {
        return "Stock[product=%s, available=%d, reserved=%d, v=%s]"
                .formatted(productId, availableQty, reservedQty, version);
    }
}
