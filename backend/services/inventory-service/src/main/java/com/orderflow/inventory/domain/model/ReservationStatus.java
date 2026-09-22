package com.orderflow.inventory.domain.model;

/**
 * Vòng đời phiếu giữ hàng.
 *
 * <pre>
 *            ┌──────────► CONFIRMED   (đơn chốt, hàng rời kho)
 *   HELD ────┼──────────► RELEASED    (đơn huỷ — compensate của saga)
 *            └──────────► EXPIRED     (quá hạn, job dọn dẹp tự nhả)
 * </pre>
 *
 * <p>Phân biệt RELEASED và EXPIRED tuy cùng trả hàng về kho nhưng khác
 * nguyên nhân — và khác nhau khi đối soát: EXPIRED nhiều bất thường nghĩa là
 * timeout đang đặt quá ngắn so với thời gian thanh toán thật.
 */
public enum ReservationStatus {

    HELD,
    CONFIRMED,
    RELEASED,
    EXPIRED;

    public boolean isActive() {
        return this == HELD;
    }

    public boolean isFinal() {
        return this != HELD;
    }
}
