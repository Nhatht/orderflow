package com.orderflow.inventory.application.port.out;

import java.util.UUID;

/**
 * CỔNG RA cho sổ ghi event đã xử lý — nền của idempotent consumer.
 */
public interface ProcessedEventPort {

    /**
     * Đánh dấu {@code eventId} là đã xử lý, NGUYÊN TỬ.
     *
     * <p>Phải được gọi BÊN TRONG transaction của thao tác nghiệp vụ, để việc
     * ghi sổ và việc giữ hàng cùng commit hoặc cùng rollback.
     *
     * @return {@code true} nếu đây là lần đầu — hãy xử lý tiếp;
     *         {@code false} nếu đã có người xử lý rồi — bỏ qua.
     */
    boolean markProcessed(UUID eventId, String operation);
}
