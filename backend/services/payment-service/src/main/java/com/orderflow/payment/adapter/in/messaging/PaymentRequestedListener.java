package com.orderflow.payment.adapter.in.messaging;

import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.payment.PaymentRequestedEvent;
import com.orderflow.payment.application.dto.ProcessPaymentCommand;
import com.orderflow.payment.application.port.in.ProcessPaymentUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Driving adapter: {@code payment.requested} → {@link ProcessPaymentUseCase}.
 *
 * <p>Hợp đồng lỗi (xem {@code OrderCreatedListener} bên inventory): cổng từ
 * chối là kết quả bình thường — use case ghi FAILED và phát event, method trả
 * về bình thường. Cổng timeout thì exception bay ra, Kafka giao lại, và lần
 * sau gọi cổng với CÙNG idempotency key.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRequestedListener {

    private final ProcessPaymentUseCase processPayment;

    @KafkaListener(topics = Topics.PAYMENT_REQUESTED)
    public void onPaymentRequested(EventEnvelope<PaymentRequestedEvent> envelope) {
        MDC.put("correlationId", envelope.correlationId());
        try {
            PaymentRequestedEvent event = envelope.payload();
            log.info("Received {} eventId={} order={} amount={} {}", envelope.eventType(),
                    envelope.eventId(), event.orderId(), event.amount().toPlainString(), event.currency());

            processPayment.process(new ProcessPaymentCommand(
                    envelope.correlationId(), event.orderId(), event.customerId(),
                    event.amount(), event.currency()));
        } finally {
            MDC.remove("correlationId");
        }
    }
}
