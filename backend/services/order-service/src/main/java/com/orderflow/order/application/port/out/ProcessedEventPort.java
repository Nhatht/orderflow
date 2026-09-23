package com.orderflow.order.application.port.out;

import java.util.UUID;

/**
 * Sổ ghi event đã xử lý — idempotent consumer. Cùng hợp đồng với inventory:
 * gọi trong transaction nghiệp vụ; trả {@code false} nghĩa là đã xử lý rồi.
 */
public interface ProcessedEventPort {

    boolean markProcessed(UUID eventId, String operation);
}
