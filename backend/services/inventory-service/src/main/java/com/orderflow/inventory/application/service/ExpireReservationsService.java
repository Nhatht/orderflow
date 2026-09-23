package com.orderflow.inventory.application.service;

import com.orderflow.inventory.application.dto.ReservationView;
import com.orderflow.inventory.application.port.in.ExpireReservationsUseCase;
import com.orderflow.inventory.application.port.out.DistributedLockPort;
import com.orderflow.inventory.application.port.out.EventPublisherPort;
import com.orderflow.inventory.application.port.out.ReservationRepositoryPort;
import com.orderflow.inventory.application.port.out.StockRepositoryPort;
import com.orderflow.inventory.domain.exception.StockNotFoundException;
import com.orderflow.inventory.domain.model.Stock;
import com.orderflow.inventory.domain.model.StockReservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Job hết hạn phiếu giữ hàng — countermeasure "timebound + undo" trong
 * {@code docs/PATTERNS.md} mục 0, cuối cùng đã có người thực thi.
 *
 * <h2>Quyết định thiết kế: inventory tự nhả, rồi BÁO saga</h2>
 * Hai phương án đã cân nhắc:
 * <ol>
 *   <li><b>Chỉ báo, không nhả</b> — phát "phiếu X hết hạn", để saga quyết định
 *       rồi gửi lệnh huỷ. Sạch về mặt "orchestrator quyết mọi thứ", nhưng hỏng
 *       đúng ở trường hợp cần job nhất: đơn KHÔNG có saga (tạo trước tuần 6,
 *       hoặc order-service đã mất dữ liệu) — không ai trả lời, hàng kẹt mãi.
 *       Volume dev đang có đúng 5 cái kẹo dừa kẹt kiểu này.</li>
 *   <li><b>Tự nhả rồi báo</b> (chọn) — hàng về kho ngay, không phụ thuộc ai
 *       còn sống. Saga nhận {@code stock.reservation-expired}: đang chờ thanh
 *       toán thì huỷ đơn; đã xong rồi thì bỏ qua.</li>
 * </ol>
 *
 * <h2>Rủi ro chấp nhận — và cách bịt</h2>
 * <b>Late response:</b> nhả hàng xong, saga huỷ đơn, rồi tiền mới về → saga
 * log {@code LATE PAYMENT ... REFUND REQUIRED}. TTL 3 phút đặt theo p99 thời
 * gian thanh toán (khách nhập OTP 30–90 giây) để việc này hiếm, không phải
 * không thể.
 *
 * <p><b>Đảo thứ tự — CHƯA bịt:</b> saga đã nhận tiền và phát
 * {@code order.confirmed}, nhưng job chạy trước khi inventory đọc event đó
 * (inventory sập vài phút rồi khởi động lại). Chốt đơn khi đó không còn phiếu
 * HELD nào nên không trừ kho — đơn đã trả tiền nhưng hàng đã về kệ. Cách bịt dự
 * kiến: đường chốt đơn gặp phiếu EXPIRED thì lấy lại hàng từ kho, hết hàng thì
 * log OVERSOLD cho người xử lý.
 *
 * <h2>Vì sao an toàn khi nhiều instance cùng chạy job</h2>
 * Không cần ShedLock: mỗi đơn được khoá (cùng khoá Redis với mọi đường giữ/nhả
 * hàng khác) và đọc lại trạng thái phiếu BÊN TRONG khoá. Instance đến sau thấy
 * phiếu đã EXPIRED và bỏ qua. Không cần {@code processed_events} vì job không
 * tiêu thụ event nào — trạng thái của chính phiếu là khoá idempotency.
 */
@Slf4j
@Service
public class ExpireReservationsService implements ExpireReservationsUseCase {

    private final StockRepositoryPort stockRepository;
    private final ReservationRepositoryPort reservationRepository;
    private final EventPublisherPort eventPublisher;
    private final DistributedLockPort lock;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Duration lockWaitTime;
    private final Duration lockLeaseTime;

    public ExpireReservationsService(StockRepositoryPort stockRepository,
                                     ReservationRepositoryPort reservationRepository,
                                     EventPublisherPort eventPublisher,
                                     DistributedLockPort lock,
                                     TransactionTemplate tx,
                                     @Value("${orderflow.inventory.expiry.batch-size:100}") int batchSize,
                                     @Value("${orderflow.inventory.lock.wait-time:PT5S}") Duration lockWaitTime,
                                     @Value("${orderflow.inventory.lock.lease-time:PT10S}") Duration lockLeaseTime) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.eventPublisher = eventPublisher;
        this.lock = lock;
        this.tx = tx;
        this.batchSize = batchSize;
        this.lockWaitTime = lockWaitTime;
        this.lockLeaseTime = lockLeaseTime;
    }

    @Override
    public int expireDue(Instant now) {
        // Gom theo đơn: mỗi đơn là một transaction + một event, giống lúc giữ hàng.
        Map<UUID, List<StockReservation>> byOrder = reservationRepository.findExpired(now, batchSize).stream()
                .collect(Collectors.groupingBy(StockReservation::orderId, LinkedHashMap::new, Collectors.toList()));

        int expired = 0;
        for (var entry : byOrder.entrySet()) {
            try {
                expired += expireOrder(entry.getKey(), entry.getValue(), now);
            } catch (RuntimeException e) {
                // Một đơn lỗi (khoá bận, DB chập chờn) không được chặn cả lô —
                // phiếu vẫn HELD, lần chạy sau sẽ thử lại.
                log.warn("Could not expire reservations of order={}: {}", entry.getKey(), e.toString());
            }
        }
        if (expired > 0) {
            log.info("Expired {} reservation(s) across {} order(s)", expired, byOrder.size());
        }
        return expired;
    }

    private int expireOrder(UUID orderId, List<StockReservation> candidates, Instant now) {
        List<String> lockKeys = candidates.stream().map(r -> stockLockKey(r.productId())).toList();

        Integer count = lock.executeWithLocks(lockKeys, lockWaitTime, lockLeaseTime, () -> tx.execute(status -> {
            List<ReservationView> expired = new ArrayList<>();
            // Đọc lại BÊN TRONG khoá: có thể đơn vừa được chốt/huỷ, hoặc instance
            // khác vừa nhả xong. isExpired() chỉ đúng với phiếu còn HELD.
            for (StockReservation reservation : reservationRepository.findByOrderId(orderId)) {
                if (!reservation.isExpired(now)) {
                    continue;
                }
                Stock stock = stockRepository.findByProductId(reservation.productId())
                        .orElseThrow(() -> new StockNotFoundException(reservation.productId()));
                stock.release(reservation.quantity());
                stockRepository.save(stock);
                reservation.expire();
                expired.add(ReservationView.from(reservationRepository.save(reservation)));
            }
            if (!expired.isEmpty()) {
                eventPublisher.publishStockReservationExpired(orderId, expired);
            }
            return expired.size();
        }));
        return count == null ? 0 : count;
    }

    private static String stockLockKey(UUID productId) {
        return "orderflow:lock:stock:" + productId;
    }
}
