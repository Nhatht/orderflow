package com.orderflow.order.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Trạng thái saga của MỘT đơn — domain thuần, không Spring/JPA/Kafka.
 *
 * <p>Toàn bộ luật "đang ở đâu thì được đi tới đâu" nằm ở đây. Orchestrator
 * chỉ hỏi {@link #status()} để quyết định có xử lý phản hồi hay không, rồi gọi
 * đúng hành vi. Mỗi hành vi ghi lại các bước vào {@link #pendingLog()} — adapter
 * lưu chúng xuống {@code saga_step_log} cho trang timeline.
 */
public class OrderSaga {

    /** Các bước của saga, theo thứ tự thuận. RELEASE_STOCK là bước ĐỀN BÙ. */
    public enum Step { RESERVE_STOCK, PROCESS_PAYMENT, CONFIRM_ORDER, RELEASE_STOCK }

    public enum Outcome { REQUESTED, SUCCEEDED, FAILED }

    public record LogEntry(Step step, Outcome outcome, String detail) {}

    private final UUID orderId;
    private final Instant startedAt;
    private final Long version;

    private SagaStatus status;
    private Step currentStep;
    private String failureReason;
    private Instant updatedAt;

    private final List<LogEntry> pendingLog = new ArrayList<>();

    public OrderSaga(UUID orderId, SagaStatus status, Step currentStep, String failureReason,
                     Instant startedAt, Instant updatedAt, Long version) {
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.currentStep = Objects.requireNonNull(currentStep, "currentStep must not be null");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.failureReason = failureReason;
        this.version = version;
    }

    /** Saga bắt đầu cùng lúc đơn được tạo: bước đầu tiên là xin giữ hàng. */
    public static OrderSaga start(UUID orderId) {
        Instant now = Instant.now();
        OrderSaga saga = new OrderSaga(orderId, SagaStatus.STARTED, Step.RESERVE_STOCK, null, now, now, null);
        saga.log(Step.RESERVE_STOCK, Outcome.REQUESTED, null);
        return saga;
    }

    // ---- Phản hồi từ các service khác ---------------------------------------

    public void stockReserved() {
        require(SagaStatus.STARTED, "stockReserved");
        log(Step.RESERVE_STOCK, Outcome.SUCCEEDED, null);
        moveTo(SagaStatus.AWAITING_PAYMENT, Step.PROCESS_PAYMENT);
        log(Step.PROCESS_PAYMENT, Outcome.REQUESTED, null);
    }

    /** Hết hàng: chưa có tác dụng phụ nào → kết thúc luôn, không cần đền bù. */
    public void stockUnavailable(String reason) {
        require(SagaStatus.STARTED, "stockUnavailable");
        log(Step.RESERVE_STOCK, Outcome.FAILED, reason);
        this.failureReason = bounded(reason);
        moveTo(SagaStatus.FAILED, Step.RESERVE_STOCK);
    }

    public void paymentCompleted() {
        require(SagaStatus.AWAITING_PAYMENT, "paymentCompleted");
        log(Step.PROCESS_PAYMENT, Outcome.SUCCEEDED, null);
        moveTo(SagaStatus.COMPLETED, Step.CONFIRM_ORDER);
        log(Step.CONFIRM_ORDER, Outcome.SUCCEEDED, null);
    }

    /** Thanh toán thất bại SAU KHI đã giữ hàng → phải đền bù: nhả hàng về kho. */
    public void paymentFailed(String reason) {
        require(SagaStatus.AWAITING_PAYMENT, "paymentFailed");
        log(Step.PROCESS_PAYMENT, Outcome.FAILED, reason);
        this.failureReason = bounded(reason);
        moveTo(SagaStatus.COMPENSATING, Step.RELEASE_STOCK);
        log(Step.RELEASE_STOCK, Outcome.REQUESTED, null);
    }

    /**
     * Chờ tiền quá hạn (saga timeout) → đền bù như thanh toán thất bại.
     *
     * <p>Khác {@link #reservationExpired()}: ở đây hàng VẪN đang được giữ, vì
     * saga timeout (3 phút) ngắn hơn hẳn TTL của phiếu (30 phút) — saga là bên
     * quyết định trước. Nên phải CHỦ ĐỘNG nhả hàng rồi chờ {@code stock.released}.
     * Xem {@code docs/SAGA-TIMEOUT.md}.
     */
    public void paymentTimedOut() {
        require(SagaStatus.AWAITING_PAYMENT, "paymentTimedOut");
        log(Step.PROCESS_PAYMENT, Outcome.FAILED, "PAYMENT_TIMEOUT");
        this.failureReason = "PAYMENT_TIMEOUT";
        moveTo(SagaStatus.COMPENSATING, Step.RELEASE_STOCK);
        log(Step.RELEASE_STOCK, Outcome.REQUESTED, null);
    }

    public void stockReleased() {
        require(SagaStatus.COMPENSATING, "stockReleased");
        log(Step.RELEASE_STOCK, Outcome.SUCCEEDED, null);
        moveTo(SagaStatus.COMPENSATED, Step.RELEASE_STOCK);
    }

    /**
     * Inventory tự thu hồi hàng vì phiếu giữ quá hạn, trong lúc saga còn chờ
     * thanh toán. Hàng đã về kho — không còn gì để đền bù — nên kết thúc FAILED
     * ngay. Tiền về sau đó là late response: orchestrator log REFUND REQUIRED.
     */
    public void reservationExpired() {
        require(SagaStatus.AWAITING_PAYMENT, "reservationExpired");
        log(Step.RESERVE_STOCK, Outcome.FAILED, "RESERVATION_EXPIRED");
        this.failureReason = "RESERVATION_EXPIRED";
        moveTo(SagaStatus.FAILED, Step.RESERVE_STOCK);
    }

    // -------------------------------------------------------------------------

    private void require(SagaStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException("Saga %s cannot %s in status %s (expected %s)"
                    .formatted(orderId, action, status, expected));
        }
    }

    private void moveTo(SagaStatus next, Step step) {
        this.status = next;
        this.currentStep = step;
        this.updatedAt = Instant.now();
    }

    private void log(Step step, Outcome outcome, String detail) {
        pendingLog.add(new LogEntry(step, outcome, bounded(detail)));
    }

    /**
     * Lý do thất bại đến từ hệ thống khác (cổng thanh toán) — độ dài không do ta
     * kiểm soát. Cắt về giới hạn cột (255) thay vì để UPDATE lỗi và làm kẹt saga.
     */
    private static String bounded(String text) {
        return text == null || text.length() <= 255 ? text : text.substring(0, 255);
    }

    /** Các bước phát sinh từ lần load gần nhất, chờ adapter lưu xuống. */
    public List<LogEntry> pendingLog() { return List.copyOf(pendingLog); }

    public UUID orderId()           { return orderId; }
    public SagaStatus status()      { return status; }
    public Step currentStep()       { return currentStep; }
    public String failureReason()   { return failureReason; }
    public Instant startedAt()      { return startedAt; }
    public Instant updatedAt()      { return updatedAt; }
    public Long version()           { return version; }
}
