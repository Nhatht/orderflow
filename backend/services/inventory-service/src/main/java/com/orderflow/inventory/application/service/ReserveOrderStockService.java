package com.orderflow.inventory.application.service;

import com.orderflow.inventory.application.dto.ReservationOutcome;
import com.orderflow.inventory.application.dto.ReservationOutcome.Rejected;
import com.orderflow.inventory.application.dto.ReservationOutcome.Rejected.Reason;
import com.orderflow.inventory.application.dto.ReservationOutcome.Rejected.Shortage;
import com.orderflow.inventory.application.dto.ReservationView;
import com.orderflow.inventory.application.dto.ReserveOrderStockCommand;
import com.orderflow.inventory.application.port.in.ReserveOrderStockUseCase;
import com.orderflow.inventory.application.port.out.DistributedLockPort;
import com.orderflow.inventory.application.port.out.EventPublisherPort;
import com.orderflow.inventory.application.port.out.ProcessedEventPort;
import com.orderflow.inventory.application.port.out.ReservationRepositoryPort;
import com.orderflow.inventory.application.port.out.StockRepositoryPort;
import com.orderflow.inventory.domain.model.Stock;
import com.orderflow.inventory.domain.model.StockReservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Giữ hàng cho cả đơn khi nhận {@code order.created} — bước 1 của saga.
 *
 * <h2>1. Tất cả hoặc không — dùng ACID ở chỗ ACID còn dùng được</h2>
 *
 * <p>Đơn có kẹo dừa và bánh tráng. Nếu giữ từng món trong transaction riêng:
 * <pre>
 *   giữ kẹo dừa     ✅ commit
 *   giữ bánh tráng  ❌ hết hàng
 *   → kẹo dừa đã bị giữ cho một đơn sẽ bị huỷ; phải viết thêm bước đền bù
 * </pre>
 * Nhưng cả hai món nằm trong CÙNG một database. Không có lý do gì phải chịu
 * eventual consistency ở đây: một transaction bọc cả đơn là đủ. Saga dành cho
 * phần XUYÊN service (kho ↔ tiền ↔ đơn), không phải để thay ACID ở nơi ACID
 * vẫn làm được. Đây là câu hỏi phỏng vấn hay: "saga có thay hoàn toàn
 * transaction không?" — Không, saga là chuỗi các transaction ACID cục bộ.
 *
 * <p>Kiểm tra ĐỦ HÀNG CHO MỌI DÒNG trước, rồi mới trừ. Nhờ vậy nhánh hết hàng
 * không đụng vào tồn kho chút nào, và không cần rollback giữa chừng.
 *
 * <h2>2. Idempotent consumer — ghi sổ trong cùng transaction</h2>
 *
 * <p>{@code processed_events} được ghi CÙNG transaction với việc giữ hàng —
 * kể cả ở nhánh hết hàng. Nhánh hết hàng mà không ghi sổ thì: lần giao lại
 * sau vài phút, kho vừa nhập thêm hàng → lần này giữ được → inventory phát
 * {@code stock.reserved} cho một đơn mà nó đã báo {@code reservation-failed}
 * trước đó. Order-service nhận hai câu trả lời trái ngược cho cùng một câu hỏi.
 * Đã trả lời thì phải trả lời nhất quán.
 *
 * <h2>3. Thứ tự: khoá → transaction → commit → nhả khoá → phát event</h2>
 *
 * <p>Khoá bọc ngoài transaction vì cùng lý do với {@link ReserveStockService}.
 * Phát event SAU cùng, ngoài cả khoá: không giữ khoá Redis trong lúc chờ Kafka.
 *
 * <p><b>Lỗ hổng còn lại (tuần 5 sửa):</b> commit xong mà chết trước khi phát
 * event thì kết quả mất. Lần giao lại gặp sổ đã ghi → {@code Duplicate} → không
 * phát gì. Đơn kẹt mãi. Outbox sửa bằng cách ghi event vào bảng outbox trong
 * CÙNG transaction này.
 */
@Slf4j
@Service
public class ReserveOrderStockService implements ReserveOrderStockUseCase {

    static final String OPERATION = "reserve-order-stock";

    private final StockRepositoryPort stockRepository;
    private final ReservationRepositoryPort reservationRepository;
    private final ProcessedEventPort processedEvents;
    private final EventPublisherPort eventPublisher;
    private final DistributedLockPort lock;
    private final TransactionTemplate tx;
    private final Duration reservationTtl;
    private final Duration lockWaitTime;
    private final Duration lockLeaseTime;

    public ReserveOrderStockService(StockRepositoryPort stockRepository,
                                    ReservationRepositoryPort reservationRepository,
                                    ProcessedEventPort processedEvents,
                                    EventPublisherPort eventPublisher,
                                    DistributedLockPort lock,
                                    TransactionTemplate tx,
                                    @Value("${orderflow.inventory.reservation-ttl:PT3M}") Duration reservationTtl,
                                    @Value("${orderflow.inventory.lock.wait-time:PT5S}") Duration lockWaitTime,
                                    @Value("${orderflow.inventory.lock.lease-time:PT10S}") Duration lockLeaseTime) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.processedEvents = processedEvents;
        this.eventPublisher = eventPublisher;
        this.lock = lock;
        this.tx = tx;
        this.reservationTtl = reservationTtl;
        this.lockWaitTime = lockWaitTime;
        this.lockLeaseTime = lockLeaseTime;
    }

    @Override
    public ReservationOutcome reserveForOrder(ReserveOrderStockCommand command) {
        Map<UUID, Integer> wanted = mergeLinesOfSameProduct(command.items());

        List<String> lockKeys = wanted.keySet().stream()
                .map(ReserveOrderStockService::stockLockKey)
                .toList();

        ReservationOutcome outcome = lock.executeWithLocks(lockKeys, lockWaitTime, lockLeaseTime, () ->
                tx.execute(status -> doReserve(command, wanted)));        // ← COMMIT + nhả khoá

        switch (outcome) {                                                  // ← phát event sau cùng
            case ReservationOutcome.Reserved r -> eventPublisher.publishStockReserved(r, command.correlationId());
            case Rejected r -> eventPublisher.publishStockReservationFailed(r, command.correlationId());
            case ReservationOutcome.Duplicate d ->
                    log.info("Duplicate delivery for order={} key={} — already handled, publishing nothing",
                            d.orderId(), command.idempotencyKey());
        }
        return outcome;
    }

    private ReservationOutcome doReserve(ReserveOrderStockCommand command, Map<UUID, Integer> wanted) {
        UUID orderId = command.orderId();

        if (!processedEvents.markProcessed(command.idempotencyKey(), OPERATION)) {
            return new ReservationOutcome.Duplicate(orderId);
        }

        Map<UUID, Stock> stocks = stockRepository.findByProductIds(new ArrayList<>(wanted.keySet())).stream()
                .collect(Collectors.toMap(Stock::productId, Function.identity()));

        // ---- Pha 1: KIỂM TRA mọi dòng, chưa đụng vào gì --------------------
        List<Shortage> shortages = new ArrayList<>();
        boolean unknownProduct = false;
        for (var line : wanted.entrySet()) {
            Stock stock = stocks.get(line.getKey());
            if (stock == null) {
                unknownProduct = true;
                shortages.add(new Shortage(line.getKey(), line.getValue(), 0));
            } else if (!stock.canReserve(line.getValue())) {
                shortages.add(new Shortage(line.getKey(), line.getValue(), stock.availableQty()));
            }
        }

        if (!shortages.isEmpty()) {
            Reason reason = unknownProduct ? Reason.UNKNOWN_PRODUCT : Reason.INSUFFICIENT_STOCK;
            log.info("Reservation rejected: order={} reason={} shortages={}", orderId, reason, shortages);
            // Không ném exception: transaction vẫn COMMIT để giữ dòng processed_events.
            return new Rejected(orderId, reason, shortages);
        }

        // ---- Pha 2: GIỮ HÀNG — đã biết chắc đủ cho mọi dòng -----------------
        // Vẫn gọi stock.reserve() (có kiểm tra lại bên trong) chứ không trừ tay:
        // luật nghiệp vụ chỉ nằm ở một chỗ là domain model.
        List<ReservationView> reservations = new ArrayList<>();
        for (var line : wanted.entrySet()) {
            Stock stock = stocks.get(line.getKey());
            stock.reserve(line.getValue());
            stockRepository.save(stock);

            StockReservation saved = reservationRepository.save(
                    StockReservation.hold(orderId, line.getKey(), line.getValue(), reservationTtl));
            reservations.add(ReservationView.from(saved));
        }

        Instant expiresAt = reservations.stream()
                .map(ReservationView::expiresAt)
                .min(Comparator.naturalOrder())
                .orElseThrow();

        log.info("Order stock reserved: order={} lines={} expires={}", orderId, reservations.size(), expiresAt);
        return new ReservationOutcome.Reserved(orderId, reservations, expiresAt);
    }

    /**
     * Gộp các dòng cùng sản phẩm: đơn có hai dòng "kẹo dừa ×1" thành "kẹo dừa ×2".
     *
     * <p>Không gộp thì dòng thứ hai vi phạm UNIQUE(order_id, product_id) của
     * bảng phiếu giữ hàng, cả transaction rollback, và consumer thử lại mãi
     * với cùng một message không bao giờ thành công.
     */
    private static Map<UUID, Integer> mergeLinesOfSameProduct(List<ReserveOrderStockCommand.Item> items) {
        return items.stream().collect(Collectors.toMap(
                ReserveOrderStockCommand.Item::productId,
                ReserveOrderStockCommand.Item::quantity,
                Integer::sum,
                LinkedHashMap::new));
    }

    /** Cùng định dạng key với {@link ReserveStockService} — hai đường vào, một ổ khoá. */
    private static String stockLockKey(UUID productId) {
        return "orderflow:lock:stock:" + productId;
    }
}
