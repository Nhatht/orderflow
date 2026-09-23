package com.orderflow.payment.application.service;

import com.orderflow.payment.application.dto.ProcessPaymentCommand;
import com.orderflow.payment.application.port.in.ProcessPaymentUseCase;
import com.orderflow.payment.application.port.out.EventPublisherPort;
import com.orderflow.payment.application.port.out.PaymentGatewayPort;
import com.orderflow.payment.application.port.out.PaymentGatewayPort.Result;
import com.orderflow.payment.application.port.out.PaymentRepositoryPort;
import com.orderflow.payment.domain.model.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Thu tiền cho một đơn — bài toán gọi hệ thống bên ngoài một cách idempotent.
 *
 * <h2>Vì sao idempotent consumer của tuần 4 KHÔNG đủ ở đây</h2>
 * Inventory làm mọi thứ trong MỘT transaction database: ghi sổ, giữ hàng, ghi
 * outbox — cùng commit hoặc cùng rollback. Payment không làm thế được, vì
 * bước quan trọng nhất (trừ tiền khách) xảy ra ở CỔNG THANH TOÁN, ngoài mọi
 * transaction của mình. Rollback không thu hồi được tiền đã trừ.
 *
 * <h2>Ba bước, hai transaction</h2>
 * <pre>
 *   TX1   tìm payment của đơn; chưa có thì tạo PENDING (id mới)   → COMMIT
 *   ───   gọi cổng thanh toán, idempotency key = payment.id        (ngoài TX)
 *   TX2   ghi COMPLETED/FAILED + ghi event vào outbox               → COMMIT
 * </pre>
 *
 * Chết ở đâu cũng an toàn:
 * <ul>
 *   <li><b>Sau TX1, trước khi gọi cổng</b> — lần sau thấy PENDING, gọi cổng.</li>
 *   <li><b>Cổng đã trừ tiền, chết trước TX2</b> — tình huống nguy hiểm nhất.
 *       Lần sau thấy PENDING, gọi cổng LẠI với CÙNG key (vì id đã commit ở
 *       TX1). Cổng nhận ra key, trả kết quả cũ, KHÔNG trừ tiền lần hai.</li>
 *   <li><b>Cổng timeout</b> — exception bay ra, Kafka giao lại, như trên.</li>
 * </ul>
 *
 * <p><b>Nếu sinh key mới cho mỗi lần gọi</b> (ví dụ {@code UUID.randomUUID()}
 * ngay trước khi gọi cổng), trường hợp thứ hai sẽ trừ tiền khách hai lần.
 * Toàn bộ thiết kế xoay quanh việc key phải được LƯU TRƯỚC khi dùng.
 *
 * <h2>Vì sao không bọc cả ba bước trong một transaction</h2>
 * <ul>
 *   <li>Giữ connection database trong lúc chờ cổng — thực tế VNPay mất 30–90
 *       giây vì khách nhập OTP. Vài chục đơn cùng lúc là cạn connection pool.</li>
 *   <li>Cổng đã trừ tiền nhưng commit fail → mất luôn bản ghi PENDING → mất
 *       luôn key → lần sau tạo payment mới với key mới → TRỪ TIỀN HAI LẦN.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessPaymentService implements ProcessPaymentUseCase {

    private final PaymentRepositoryPort paymentRepository;
    private final PaymentGatewayPort gateway;
    private final EventPublisherPort eventPublisher;
    private final TransactionTemplate tx;

    @Override
    public Payment process(ProcessPaymentCommand command) {
        Payment payment = findOrInitiate(command);                          // TX1

        if (payment.status().isFinal()) {
            // Đã xong từ lần trước: kết quả đã nằm trong outbox cùng TX2 của
            // lần đó. Không gọi cổng, không ghi event lần nữa.
            log.info("Payment for order={} already {}, nothing to do", payment.orderId(), payment.status());
            return payment;
        }

        Result result = gateway.charge(payment.id(), payment.amount(), payment.currency());   // ngoài TX

        return tx.execute(status -> recordResult(payment, result, command.correlationId())); // TX2
    }

    private Payment findOrInitiate(ProcessPaymentCommand command) {
        try {
            return tx.execute(status -> paymentRepository.findByOrderId(command.orderId())
                    .orElseGet(() -> paymentRepository.save(Payment.initiate(
                            command.orderId(), command.customerId(), command.amount(), command.currency()))));
        } catch (DataIntegrityViolationException race) {
            // Hai lần giao cùng lúc (rebalance) cùng thấy "chưa có" và cùng
            // INSERT. UNIQUE(order_id) để một bên thắng; bên thua đọc lại
            // payment của bên thắng và đi tiếp với ĐÚNG key đó.
            log.info("Concurrent initiation for order={}, using the winner's payment", command.orderId());
            return tx.execute(status -> paymentRepository.findByOrderId(command.orderId()).orElseThrow());
        }
    }

    private Payment recordResult(Payment initiated, Result result, String correlationId) {
        // Đọc lại: trong lúc ta chờ cổng, một lần giao khác có thể đã ghi xong.
        Payment payment = paymentRepository.findByOrderId(initiated.orderId()).orElseThrow();
        if (payment.status().isFinal()) {
            return payment;
        }

        switch (result) {
            case Result.Approved approved -> payment.complete(approved.gatewayReference());
            case Result.Declined declined -> payment.fail(declined.reason());
        }
        Payment saved = paymentRepository.save(payment);   // @Version chặn hai bên cùng ghi

        switch (saved.status()) {
            case COMPLETED -> eventPublisher.publishPaymentCompleted(saved, correlationId);
            case FAILED -> eventPublisher.publishPaymentFailed(saved, correlationId);
            case PENDING -> throw new IllegalStateException("unreachable: " + saved);
        }

        log.info("Payment {} for order={} amount={} {}", saved.status(), saved.orderId(),
                saved.amount().toPlainString(), saved.currency());
        return saved;
    }
}
