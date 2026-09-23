package com.orderflow.payment.adapter.out.gateway;

import com.orderflow.payment.application.port.out.PaymentGatewayPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cổng thanh toán GIẢ LẬP — thay cho VNPay/Stripe trong môi trường dev.
 *
 * <p><b>Luật quyết định (để test được nhánh đền bù của saga):</b> số tiền có
 * phần nguyên tận cùng bằng {@code 99} thì TỪ CHỐI, còn lại chấp nhận.
 * Ví dụ 45 099 VND → từ chối; 45 000 VND → chấp nhận. Đặt đơn giá 45099 là
 * cách tái hiện "thẻ bị từ chối" từ API mà không cần sửa code.
 *
 * <p>Chỉ xét PHẦN NGUYÊN: 45 099,50 bị từ chối, 45 000,99 được chấp nhận. Luật
 * giả lập, không phải luật nghiệp vụ — chỉ cần đủ để chủ động gây lỗi khi test.
 *
 * <p><b>Mô phỏng đúng hành vi idempotency của cổng thật:</b> gọi lại cùng key
 * thì trả kết quả đã lưu, không trừ tiền lần nữa. Có đếm số lần cổng THẬT SỰ xử lý một giao dịch
 * ({@link #timesProcessed}) để test chứng minh mỗi key chỉ được xử lý một lần — tức khách không bao giờ bị trừ tiền hai lần.
 *
 * <p><b>Giới hạn phải nhớ:</b> bản ghi idempotency nằm trong RAM, restart là
 * mất. Cổng thật giữ nó phía server (Stripe giữ key 24 giờ). Đó là lý do
 * adapter thật phải là HTTP client gọi ra ngoài, không phải class này.
 */
@Slf4j
@Component
public class SimulatedPaymentGateway implements PaymentGatewayPort {

    private final Duration latency;
    private final Map<UUID, Result> resultsByKey = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> processedByKey = new ConcurrentHashMap<>();
    private final AtomicInteger totalProcessed = new AtomicInteger();

    public SimulatedPaymentGateway(@Value("${orderflow.payment.gateway.latency:PT0.2S}") Duration latency) {
        this.latency = latency;
    }

    @Override
    public Result charge(UUID idempotencyKey, BigDecimal amount, String currency) {
        // computeIfAbsent nguyên tử theo key: hai lần gọi đồng thời cùng key
        // thì chỉ một lần chạy decide(), lần kia nhận chung kết quả.
        return resultsByKey.computeIfAbsent(idempotencyKey, key -> {
            simulateNetworkLatency();
            processedByKey.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            totalProcessed.incrementAndGet();
            Result result = decide(amount);
            log.info("Gateway processed key={} amount={} {} -> {}", key, amount.toPlainString(), currency, result);
            return result;
        });
    }

    static Result decide(BigDecimal amount) {
        int lastTwoDigits = amount.toBigInteger().mod(BigDecimal.valueOf(100).toBigInteger()).intValue();
        return lastTwoDigits == 99
                ? new Result.Declined("CARD_DECLINED")
                : new Result.Approved("SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }

    /** Số lần cổng thật sự xử lý (quyết định trừ hoặc từ chối) key này — chỉ để test. */
    public int timesProcessed(UUID idempotencyKey) {
        var counter = processedByKey.get(idempotencyKey);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Tổng số giao dịch cổng đã xử lý, với MỌI key — chỉ để test.
     *
     * <p>Cần cái này vì {@link #timesProcessed} không bắt được lỗi nguy hiểm
     * nhất: code sinh key MỚI cho lần gọi lại. Khi đó key cũ vẫn đếm 1, còn
     * lần trừ tiền thứ hai nằm dưới một key khác — vô hình với phép đếm theo
     * key. (Đã xảy ra thật: test đếm theo key vẫn xanh khi cố tình cài lỗi.)
     */
    public int totalProcessed() {
        return totalProcessed.get();
    }

    private void simulateNetworkLatency() {
        try {
            Thread.sleep(latency.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling payment gateway", e);
        }
    }
}
