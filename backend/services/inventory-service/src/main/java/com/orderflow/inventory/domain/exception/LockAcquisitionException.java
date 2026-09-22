package com.orderflow.inventory.domain.exception;

/**
 * Không lấy được distributed lock trong thời gian chờ cho phép.
 *
 * <p>Nghĩa là sản phẩm đang bị tranh chấp nặng. Trả 503 kèm gợi ý thử lại
 * thì đúng hơn là chờ vô hạn — chờ vô hạn dưới tải cao sẽ làm cạn thread pool
 * và kéo sập cả service.
 */
public class LockAcquisitionException extends RuntimeException {

    private final String lockKey;

    public LockAcquisitionException(String lockKey) {
        super("Could not acquire lock: " + lockKey);
        this.lockKey = lockKey;
    }

    public LockAcquisitionException(String lockKey, Throwable cause) {
        super("Could not acquire lock: " + lockKey, cause);
        this.lockKey = lockKey;
    }

    public String lockKey() { return lockKey; }
}
