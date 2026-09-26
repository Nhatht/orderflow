package com.orderflow.payment.application.service;

import com.orderflow.payment.application.port.in.CancelOrderPaymentUseCase;
import com.orderflow.payment.application.port.out.CancelledOrderPort;
import com.orderflow.payment.application.port.out.PaymentRepositoryPort;
import com.orderflow.payment.domain.model.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Ghi "bia mộ" cho đơn đã huỷ, để lệnh thu tiền đến SAU (hoặc đang chờ trong
 * Kafka) không bao giờ trừ tiền khách. Xem {@code V2__create_cancelled_orders.sql}.
 *
 * <p><b>"Lệnh đền bù đến trước lệnh gốc"</b> — bài toán kinh điển của saga.
 * {@code payment.requested} và {@code order.cancelled} là hai topic khác nhau,
 * Kafka không bảo đảm thứ tự giữa chúng. Nếu chỉ phản ứng khi lệnh huỷ tới
 * ("có payment nào thì huỷ nó") thì lệnh huỷ đến trước sẽ không thấy gì để
 * huỷ, rồi lệnh thu tiền đến sau vẫn trừ tiền. Nên phải GHI LẠI việc huỷ.
 *
 * <p>Idempotent tự nhiên: {@code INSERT ... ON CONFLICT DO NOTHING}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelOrderPaymentService implements CancelOrderPaymentUseCase {

    private final CancelledOrderPort cancelledOrders;
    private final PaymentRepositoryPort payments;
    private final TransactionTemplate tx;

    @Override
    public void onOrderCancelled(UUID orderId, String reason) {
        tx.executeWithoutResult(status -> {
            if (!cancelledOrders.markCancelled(orderId, reason)) {
                log.info("Order={} already recorded as cancelled, ignoring duplicate", orderId);
                return;
            }
            payments.findByOrderId(orderId).ifPresentOrElse(payment -> {
                if (payment.status() == PaymentStatus.COMPLETED) {
                    // Tiền đã trừ trước khi biết đơn bị huỷ. Không thu hồi được
                    // bằng cách huỷ — phải hoàn tiền (chưa tự động).
                    log.error("Order={} cancelled ({}) but payment {} already COMPLETED — REFUND REQUIRED",
                            orderId, reason, payment.id());
                } else if (payment.status() == PaymentStatus.PENDING) {
                    // Lời gọi cổng có thể đang bay. Nếu nó chưa bắt đầu (app chết
                    // sau TX1), lần thử lại sẽ thấy bia mộ và không gọi cổng.
                    log.warn("Order={} cancelled ({}) while payment {} is PENDING — a charge may be in flight",
                            orderId, reason, payment.id());
                } else {
                    log.info("Order={} cancelled ({}), payment {} already {}", orderId, reason,
                            payment.id(), payment.status());
                }
            }, () -> log.info("Order={} cancelled ({}) before any payment request — will never be charged",
                    orderId, reason));
        });
    }
}
