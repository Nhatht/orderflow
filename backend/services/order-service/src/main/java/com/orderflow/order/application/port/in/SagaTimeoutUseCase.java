package com.orderflow.order.application.port.in;

import java.time.Instant;

/**
 * Quét saga chờ tiền quá hạn và huỷ chúng. {@code now} truyền vào thay vì tự
 * gọi {@code Instant.now()} để test chọn được "bây giờ" tuỳ ý.
 */
public interface SagaTimeoutUseCase {

    /** @return số saga đã bị huỷ ở lần quét này */
    int timeOutStalePayments(Instant now);
}
