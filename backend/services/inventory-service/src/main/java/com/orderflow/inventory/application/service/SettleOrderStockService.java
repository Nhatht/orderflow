package com.orderflow.inventory.application.service;

import com.orderflow.inventory.application.dto.ReservationView;
import com.orderflow.inventory.application.port.in.SettleOrderStockUseCase;
import com.orderflow.inventory.application.port.out.DistributedLockPort;
import com.orderflow.inventory.application.port.out.EventPublisherPort;
import com.orderflow.inventory.application.port.out.ProcessedEventPort;
import com.orderflow.inventory.application.port.out.ReservationRepositoryPort;
import com.orderflow.inventory.application.port.out.StockRepositoryPort;
import com.orderflow.inventory.domain.exception.InsufficientStockException;
import com.orderflow.inventory.domain.exception.StockNotFoundException;
import com.orderflow.inventory.domain.model.ReservationStatus;
import com.orderflow.inventory.domain.model.Stock;
import com.orderflow.inventory.domain.model.StockReservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Chốt hoặc nhả toàn bộ hàng đang giữ của một đơn khi saga có kết cục.
 *
 * <p>Cùng khuôn với {@link ReserveOrderStockService}: khoá mọi sản phẩm (đã
 * sắp xếp) → một transaction: sổ {@code processed_events} + thay đổi tồn kho
 * + event trong outbox → commit → nhả khoá.
 *
 * <p><b>Nhả hàng là bước ĐỀN BÙ — không phải rollback.</b> Phiếu giữ hàng
 * không bị xoá; nó chuyển sang RELEASED và ở lại làm bằng chứng. Tồn kho quay
 * về cột {@code available}. Rollback là "như chưa từng xảy ra"; đền bù là
 * "đã xảy ra, và đây là hành động ngược lại, có dấu vết".
 */
@Slf4j
@Service
public class SettleOrderStockService implements SettleOrderStockUseCase {

    private final StockRepositoryPort stockRepository;
    private final ReservationRepositoryPort reservationRepository;
    private final ProcessedEventPort processedEvents;
    private final EventPublisherPort eventPublisher;
    private final DistributedLockPort lock;
    private final TransactionTemplate tx;
    private final Duration lockWaitTime;
    private final Duration lockLeaseTime;

    public SettleOrderStockService(StockRepositoryPort stockRepository,
                                   ReservationRepositoryPort reservationRepository,
                                   ProcessedEventPort processedEvents,
                                   EventPublisherPort eventPublisher,
                                   DistributedLockPort lock,
                                   TransactionTemplate tx,
                                   @Value("${orderflow.inventory.lock.wait-time:PT5S}") Duration lockWaitTime,
                                   @Value("${orderflow.inventory.lock.lease-time:PT10S}") Duration lockLeaseTime) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.processedEvents = processedEvents;
        this.eventPublisher = eventPublisher;
        this.lock = lock;
        this.tx = tx;
        this.lockWaitTime = lockWaitTime;
        this.lockLeaseTime = lockLeaseTime;
    }

    /**
     * Chốt hàng. Ngoài phiếu HELD, còn nhận cả phiếu EXPIRED — <b>phương án D</b>
     * trong {@code docs/SAGA-TIMEOUT.md}: lưới an toàn cho cuộc đua giữa job
     * hết hạn và {@code order.confirmed}.
     *
     * <p>Cuộc đua: saga đã nhận tiền và phát {@code order.confirmed}, nhưng trước
     * khi inventory đọc tới event đó (inventory tụt lại, hoặc order-service sập
     * quá TTL rồi mới xử lý tiền về), job đã thấy phiếu quá hạn và trả hàng về
     * kệ. Trước đây đường chốt BỎ QUA phiếu EXPIRED mà không báo gì: đơn đã trả
     * tiền, hàng không bị trừ — oversell âm thầm.
     */
    @Override
    public void confirmForOrder(UUID idempotencyKey, String correlationId, UUID orderId) {
        settle(idempotencyKey, orderId, "confirm-order-stock",
                r -> r.status().isActive() || r.status() == ReservationStatus.EXPIRED,
                (stock, reservation) -> {
                    if (reservation.status() == ReservationStatus.EXPIRED) {
                        reclaimExpired(orderId, stock, reservation);
                    } else {
                        stock.confirm(reservation.quantity());     // hàng rời kho: chỉ trừ cột reserved
                        reservation.confirm();
                    }
                }, settled -> { /* chốt đơn không cần báo lại ai */ });
    }

    /**
     * Còn hàng → lấy lại từ {@code available}, khách không hề biết. Hết hàng (đã
     * bán cho người khác trong lúc phiếu hết hạn) → OVERSOLD: log ERROR để người
     * vận hành hoàn tiền hoặc nhập thêm hàng. KHÔNG ném lỗi — thử lại cũng không
     * tự sinh ra hàng, chỉ làm kẹt partition.
     */
    private void reclaimExpired(UUID orderId, Stock stock, StockReservation reservation) {
        try {
            stock.confirmFromAvailable(reservation.quantity());
            reservation.confirmAfterExpiry();
            log.warn("RECLAIMED: order={} product={} qty={} was confirmed after its reservation expired; "
                            + "stock taken back from available",
                    orderId, reservation.productId(), reservation.quantity());
        } catch (InsufficientStockException e) {
            log.error("OVERSOLD: order={} product={} qty={} was paid and confirmed but its reservation expired "
                            + "and only {} left — REFUND OR RESTOCK REQUIRED",
                    orderId, reservation.productId(), reservation.quantity(), stock.availableQty());
        }
    }

    /**
     * @param sagaAwaitsAck saga đang ở COMPENSATING và CHỜ {@code stock.released}
     *        (đơn huỷ vì thanh toán thất bại). Khi đó LUÔN phát event xác nhận —
     *        kể cả khi không còn gì để nhả (phiếu đã hết hạn và được job dọn dẹp
     *        nhả trước, hoặc đã nhả ở lần giao trước). Không phát thì saga treo
     *        ở COMPENSATING mãi. Phát hiện khi review tuần 6.
     */
    @Override
    public void releaseForOrder(UUID idempotencyKey, String correlationId, UUID orderId, boolean sagaAwaitsAck) {
        settle(idempotencyKey, orderId, "release-order-stock", r -> r.status().isActive(), (stock, reservation) -> {
            stock.release(reservation.quantity());     // ĐỀN BÙ: reserved → available
            reservation.release();
        }, released -> {
            if (sagaAwaitsAck || !released.isEmpty()) {
                eventPublisher.publishStockReleased(orderId, released, correlationId);
            }
        });
    }

    /**
     * @param eligible phiếu nào được xử lý — nhả chỉ đụng phiếu HELD, chốt thì
     *        đụng cả phiếu EXPIRED (phương án D)
     */
    private void settle(UUID idempotencyKey, UUID orderId, String operation,
                        Predicate<StockReservation> eligible,
                        BiConsumer<Stock, StockReservation> apply,
                        Consumer<List<ReservationView>> onSettled) {
        // Đọc NGOÀI khoá chỉ để biết cần khoá sản phẩm nào.
        Set<UUID> lockedProducts = reservationRepository.findByOrderId(orderId).stream()
                .filter(eligible)
                .map(StockReservation::productId)
                .collect(Collectors.toSet());

        Supplier<Void> work = () -> tx.execute(status -> {
            // Sổ được ghi cả khi không có gì để nhả — nhờ vậy event xác nhận
            // (nếu phải phát) chỉ phát MỘT lần dù message được giao lại.
            if (!processedEvents.markProcessed(idempotencyKey, operation)) {
                log.info("{}: duplicate delivery key={} order={}, ignoring", operation, idempotencyKey, orderId);
                return null;
            }

            List<ReservationView> settled = new ArrayList<>();
            for (StockReservation reservation : reservationRepository.findByOrderId(orderId)) {
                if (!eligible.test(reservation)) {
                    continue;
                }
                // Đọc lại BÊN TRONG khoá; nếu xuất hiện phiếu của sản phẩm mà ta
                // KHÔNG khoá (không nên xảy ra — phiếu của một đơn được tạo một lần,
                // trước mọi kết cục), thì dừng và để Kafka giao lại, thay vì sửa
                // tồn kho khi không giữ khoá của nó.
                if (!lockedProducts.contains(reservation.productId())) {
                    throw new IllegalStateException("Reservation %s for unlocked product %s appeared, retrying"
                            .formatted(reservation.id(), reservation.productId()));
                }
                Stock stock = stockRepository.findByProductId(reservation.productId())
                        .orElseThrow(() -> new StockNotFoundException(reservation.productId()));
                apply.accept(stock, reservation);
                stockRepository.save(stock);
                settled.add(ReservationView.from(reservationRepository.save(reservation)));
            }

            onSettled.accept(settled);
            log.info("{}: order={} settled {} reservation(s)", operation, orderId, settled.size());
            return null;
        });

        if (lockedProducts.isEmpty()) {
            work.get();   // không có tồn kho nào bị đụng → không cần khoá
        } else {
            lock.executeWithLocks(lockedProducts.stream().map(SettleOrderStockService::stockLockKey).toList(),
                    lockWaitTime, lockLeaseTime, work);
        }
    }

    /** Cùng định dạng key với các service giữ hàng — một sản phẩm, một ổ khoá. */
    private static String stockLockKey(UUID productId) {
        return "orderflow:lock:stock:" + productId;
    }
}
