package com.orderflow.inventory.application.service;

import com.orderflow.inventory.application.dto.ReservationView;
import com.orderflow.inventory.application.dto.ReserveStockCommand;
import com.orderflow.inventory.application.port.in.ReserveStockUseCase;
import com.orderflow.inventory.application.port.out.DistributedLockPort;
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
import java.util.UUID;

/**
 * Use case giữ / nhả / chốt hàng.
 *
 * <p><b>ĐIỂM QUAN TRỌNG NHẤT CỦA CẢ TUẦN 3 — THỨ TỰ LOCK VÀ TRANSACTION.</b>
 *
 * <p>Cách viết mà rất nhiều người làm, và nó SAI:
 * <pre>{@code
 * @Transactional                       // (1) transaction mở
 * public void reserve(...) {
 *     lock.executeWithLock(key, () -> {   // (2) khoá lấy BÊN TRONG
 *         stock.reserve(qty);
 *         repo.save(stock);
 *     });                                 // (3) KHOÁ NHẢ ở đây
 * }                                    // (4) transaction mới COMMIT ở đây
 * }</pre>
 *
 * <p>Giữa (3) và (4) có một khoảng hở: khoá đã nhả nhưng dữ liệu chưa commit.
 * Luồng thứ hai lấy được khoá ngay, đọc tồn kho và thấy giá trị CŨ — vì
 * transaction của luồng một chưa commit. Thế là cả hai cùng giữ chiếc áo
 * cuối cùng. Khoá vẫn hoạt động đúng, nhưng nó bảo vệ nhầm phạm vi.
 *
 * <p>Cách đúng là <b>khoá bọc NGOÀI transaction</b>:
 * <pre>
 *   lấy khoá → mở transaction → đọc/sửa/ghi → COMMIT → nhả khoá
 * </pre>
 *
 * <p>Để làm được điều đó phải dùng {@link TransactionTemplate} thay vì
 * {@code @Transactional}. Lý do: annotation gắn vào cả method, không cho phép
 * mở transaction ở giữa method. (Tách sang một bean khác cũng được, nhưng
 * {@code TransactionTemplate} thể hiện ý đồ rõ ràng ngay tại chỗ.)
 *
 * <p><b>Ba lớp bảo vệ chồng lên nhau</b> — không dựa vào một lớp duy nhất:
 * <ol>
 *   <li>Redis distributed lock (class này) — chặn tranh chấp giữa các instance</li>
 *   <li>{@code @Version} optimistic lock — chặn lost update nếu khoá hết hạn sớm</li>
 *   <li>CHECK {@code available_qty >= 0} — chốt chặn cuối ở database</li>
 * </ol>
 */
@Slf4j
@Service
public class ReserveStockService implements ReserveStockUseCase {

    private final StockRepositoryPort stockRepository;
    private final ReservationRepositoryPort reservationRepository;
    private final DistributedLockPort lock;
    private final TransactionTemplate tx;
    private final Duration reservationTtl;
    private final Duration lockWaitTime;
    private final Duration lockLeaseTime;

    public ReserveStockService(StockRepositoryPort stockRepository,
                               ReservationRepositoryPort reservationRepository,
                               DistributedLockPort lock,
                               TransactionTemplate tx,
                               @Value("${orderflow.inventory.reservation-ttl:PT3M}") Duration reservationTtl,
                               @Value("${orderflow.inventory.lock.wait-time:PT5S}") Duration lockWaitTime,
                               @Value("${orderflow.inventory.lock.lease-time:PT10S}") Duration lockLeaseTime) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.lock = lock;
        this.tx = tx;
        this.reservationTtl = reservationTtl;
        this.lockWaitTime = lockWaitTime;
        this.lockLeaseTime = lockLeaseTime;
    }

    @Override
    public ReservationView reserve(ReserveStockCommand command) {
        String lockKey = stockLockKey(command.productId());

        // KHOÁ BỌC NGOÀI — xem javadoc của class về lý do.
        return lock.executeWithLock(lockKey, lockWaitTime, lockLeaseTime, () ->
                tx.execute(status -> doReserve(command)));
    }

    private ReservationView doReserve(ReserveStockCommand command) {
        // IDEMPOTENCY: Kafka là at-least-once, event OrderCreated có thể đến
        // hai lần. Thấy phiếu cũ thì trả về luôn, KHÔNG giữ hàng thêm lần nữa.
        // Ràng buộc UNIQUE(order_id, product_id) dưới database là lớp chặn
        // thứ hai, phòng khi hai luồng cùng qua được kiểm tra này.
        var existing = reservationRepository
                .findByOrderIdAndProductId(command.orderId(), command.productId());
        if (existing.isPresent()) {
            log.debug("Reservation already exists for order={} product={}, returning existing",
                    command.orderId(), command.productId());
            return ReservationView.from(existing.get());
        }

        Stock stock = stockRepository.findByProductId(command.productId())
                .orElseThrow(() -> new StockNotFoundException(command.productId()));

        // Ném InsufficientStockException nếu không đủ — saga bắt và đi nhánh compensate.
        stock.reserve(command.quantity());
        stockRepository.save(stock);

        StockReservation reservation = StockReservation.hold(
                command.orderId(), command.productId(), command.quantity(), reservationTtl);
        StockReservation saved = reservationRepository.save(reservation);

        log.info("Stock reserved: order={}, product={}, qty={}, available now={}, expires={}",
                command.orderId(), command.productId(), command.quantity(),
                stock.availableQty(), saved.expiresAt());

        return ReservationView.from(saved);
    }

    /**
     * Nhả hàng — BƯỚC COMPENSATE của saga.
     *
     * <p>Cố tình KHÔNG ném lỗi khi không tìm thấy phiếu, hoặc phiếu đã ở trạng
     * thái cuối. Lý do: compensate phải idempotent. Saga có thể gọi lại sau khi
     * service restart giữa chừng, và "đã nhả rồi" là kết quả đúng chứ không
     * phải lỗi. Ném lỗi ở đây sẽ làm saga kẹt vĩnh viễn ở bước đền bù.
     */
    @Override
    public void release(UUID orderId, UUID productId) {
        lock.executeWithLock(stockLockKey(productId), lockWaitTime, lockLeaseTime, () ->
                tx.execute(status -> {
                    var found = reservationRepository.findByOrderIdAndProductId(orderId, productId);
                    if (found.isEmpty()) {
                        log.warn("Release called but no reservation found: order={} product={}",
                                orderId, productId);
                        return null;
                    }

                    StockReservation reservation = found.get();
                    if (reservation.status().isFinal()) {
                        log.debug("Reservation {} already {}, nothing to release",
                                reservation.id(), reservation.status());
                        return null;
                    }

                    Stock stock = stockRepository.findByProductId(productId)
                            .orElseThrow(() -> new StockNotFoundException(productId));
                    stock.release(reservation.quantity());
                    stockRepository.save(stock);

                    reservation.release();
                    reservationRepository.save(reservation);

                    log.info("Stock released: order={}, product={}, qty={}, available now={}",
                            orderId, productId, reservation.quantity(), stock.availableQty());
                    return null;
                }));
    }

    /** Chốt đơn — hàng rời kho thật sự. Cũng idempotent như {@link #release}. */
    @Override
    public void confirm(UUID orderId, UUID productId) {
        lock.executeWithLock(stockLockKey(productId), lockWaitTime, lockLeaseTime, () ->
                tx.execute(status -> {
                    var found = reservationRepository.findByOrderIdAndProductId(orderId, productId);
                    if (found.isEmpty()) {
                        log.warn("Confirm called but no reservation found: order={} product={}",
                                orderId, productId);
                        return null;
                    }

                    StockReservation reservation = found.get();
                    if (reservation.status().isFinal()) {
                        log.debug("Reservation {} already {}, nothing to confirm",
                                reservation.id(), reservation.status());
                        return null;
                    }

                    Stock stock = stockRepository.findByProductId(productId)
                            .orElseThrow(() -> new StockNotFoundException(productId));
                    stock.confirm(reservation.quantity());
                    stockRepository.save(stock);

                    reservation.confirm();
                    reservationRepository.save(reservation);

                    log.info("Stock confirmed: order={}, product={}, qty={}",
                            orderId, productId, reservation.quantity());
                    return null;
                }));
    }

    /**
     * Khoá theo TỪNG SẢN PHẨM, không khoá toàn kho.
     *
     * <p>Người mua kẹo dừa và người mua bánh tráng không chặn nhau —
     * chỉ những ai tranh cùng một sản phẩm mới phải xếp hàng. Khoá quá rộng
     * là cách nhanh nhất để giết thông lượng.
     */
    private String stockLockKey(UUID productId) {
        return "orderflow:lock:stock:" + productId;
    }
}
