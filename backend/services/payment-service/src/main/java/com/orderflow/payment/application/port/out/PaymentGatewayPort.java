package com.orderflow.payment.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * CỔNG RA tới cổng thanh toán bên ngoài (VNPay, Stripe...).
 *
 * <p><b>Hợp đồng bắt buộc với mọi cài đặt:</b> gọi {@link #charge} nhiều lần
 * với CÙNG {@code idempotencyKey} thì chỉ trừ tiền MỘT lần, các lần sau trả về
 * đúng kết quả của lần đầu. Cổng thanh toán thật đều hỗ trợ điều này (Stripe:
 * header {@code Idempotency-Key}; VNPay: mã giao dịch {@code vnp_TxnRef} là
 * duy nhất). Thiếu nó thì không có cách nào thử lại an toàn sau khi mất kết nối.
 *
 * <p>Hai loại kết cục, xử lý khác hẳn nhau:
 * <ul>
 *   <li><b>Trả về {@link Result}</b> — cổng đã TRẢ LỜI (đồng ý hoặc từ chối).
 *       Có kết quả chắc chắn, ghi xuống được.</li>
 *   <li><b>Ném exception</b> — timeout, mất kết nối: CHƯA BIẾT tiền đã bị trừ
 *       hay chưa. Tuyệt đối không được coi là "từ chối". Để exception bay lên,
 *       Kafka giao lại, lần sau gọi lại với cùng key để biết sự thật.</li>
 * </ul>
 */
public interface PaymentGatewayPort {

    Result charge(UUID idempotencyKey, BigDecimal amount, String currency);

    sealed interface Result {
        record Approved(String gatewayReference) implements Result {}
        record Declined(String reason) implements Result {}
    }
}
