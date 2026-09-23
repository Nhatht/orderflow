package com.orderflow.contracts.payment;

import java.util.UUID;

/**
 * Cổng thanh toán TỪ CHỐI — kết quả nghiệp vụ (thẻ hết tiền, sai OTP...).
 *
 * <p>Giống {@code StockReservationFailedEvent}: lỗi hạ tầng (cổng thanh toán
 * không trả lời, timeout) KHÔNG sinh ra event này. Timeout nghĩa là "chưa
 * biết" — có thể tiền đã bị trừ. Coi "chưa biết" là "thất bại" rồi huỷ đơn
 * chính là cách tạo ra khách bị trừ tiền mà không có hàng.
 */
public record PaymentFailedEvent(
        UUID orderId,
        UUID paymentId,
        String reason
) {
    public static final String TYPE = "PaymentFailed";
}
