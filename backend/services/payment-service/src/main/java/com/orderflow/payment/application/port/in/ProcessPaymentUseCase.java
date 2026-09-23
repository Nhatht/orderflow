package com.orderflow.payment.application.port.in;

import com.orderflow.payment.application.dto.ProcessPaymentCommand;
import com.orderflow.payment.domain.model.Payment;

public interface ProcessPaymentUseCase {

    /**
     * Thu tiền cho đơn. Idempotent theo orderId: gọi lại bao nhiêu lần cũng chỉ
     * trừ tiền khách một lần và chỉ phát một event kết quả.
     *
     * @return payment ở trạng thái cuối (COMPLETED hoặc FAILED)
     */
    Payment process(ProcessPaymentCommand command);
}
