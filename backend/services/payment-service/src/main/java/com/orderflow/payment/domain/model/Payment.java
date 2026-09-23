package com.orderflow.payment.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Một lần thu tiền cho một đơn — aggregate root của payment-service.
 *
 * <p>Domain thuần: không Spring, không JPA, không biết cổng thanh toán nào.
 *
 * <p><b>{@link #id()} đồng thời là idempotency key gửi sang cổng thanh toán.</b>
 * Nó được sinh MỘT lần trong {@link #initiate} và lưu xuống database trước khi
 * gọi cổng — xem {@code ProcessPaymentService}.
 *
 * <p>Tiền giữ dạng {@code BigDecimal} scale 4 ngay trong class này, không dùng
 * chung {@code Money} của order-service: mỗi service sở hữu domain model của
 * riêng mình. Dùng chung là kéo cả hai service vào cùng một thư viện nghiệp vụ.
 */
public class Payment {

    private static final int SCALE = 4;

    private final UUID id;
    private final UUID orderId;
    private final UUID customerId;
    private final BigDecimal amount;
    private final String currency;
    private final Instant createdAt;

    private PaymentStatus status;
    private String failureReason;
    private String gatewayReference;
    private Instant updatedAt;
    private final Long version;

    public Payment(UUID id, UUID orderId, UUID customerId, BigDecimal amount, String currency,
                   PaymentStatus status, String failureReason, String gatewayReference,
                   Instant createdAt, Instant updatedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, got: " + amount);
        }
        this.amount = amount.setScale(SCALE, RoundingMode.HALF_UP);

        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO 4217 code, got: " + currency);
        }
        this.currency = currency.toUpperCase();

        this.failureReason = failureReason;
        this.gatewayReference = gatewayReference;
        this.version = version;
    }

    /** Quyết định thu tiền cho đơn. Chưa gọi cổng — chỉ ghi nhận ý định. */
    public static Payment initiate(UUID orderId, UUID customerId, BigDecimal amount, String currency) {
        Instant now = Instant.now();
        return new Payment(UUID.randomUUID(), orderId, customerId, amount, currency,
                PaymentStatus.PENDING, null, null, now, now, null);
    }

    /** Cổng xác nhận đã thu. */
    public void complete(String gatewayReference) {
        requirePending("complete");
        this.gatewayReference = Objects.requireNonNull(gatewayReference, "gatewayReference must not be null");
        this.status = PaymentStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    /** Cổng từ chối. */
    public void fail(String reason) {
        requirePending("fail");
        this.failureReason = Objects.requireNonNull(reason, "reason must not be null");
        this.status = PaymentStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    /**
     * Đã có kết quả thì không đổi được nữa. Một khoản đã thu thì không bao giờ
     * tự "chưa thu" lại — muốn trả tiền thì phải là một giao dịch HOÀN TIỀN mới,
     * có dấu vết riêng. Đó là khác biệt giữa compensate và rollback.
     */
    private void requirePending(String action) {
        if (status.isFinal()) {
            throw new IllegalStateException(
                    "Cannot %s payment %s: already %s".formatted(action, id, status));
        }
    }

    public UUID id()                  { return id; }
    public UUID orderId()             { return orderId; }
    public UUID customerId()          { return customerId; }
    public BigDecimal amount()        { return amount; }
    public String currency()          { return currency; }
    public PaymentStatus status()     { return status; }
    public String failureReason()     { return failureReason; }
    public String gatewayReference()  { return gatewayReference; }
    public Instant createdAt()        { return createdAt; }
    public Instant updatedAt()        { return updatedAt; }
    public Long version()             { return version; }

    @Override
    public String toString() {
        return "Payment[id=%s, order=%s, amount=%s %s, status=%s]"
                .formatted(id, orderId, amount.toPlainString(), currency, status);
    }
}
