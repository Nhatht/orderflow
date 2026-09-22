package com.orderflow.order.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value object biểu diễn số tiền.
 *
 * <p>Vì sao không dùng thẳng {@code BigDecimal}:
 * <ul>
 *   <li>{@code BigDecimal} trần không mang đơn vị tiền tệ — cộng 100 USD với
 *       100 VND vẫn chạy, và sai lặng lẽ. Ở đây cộng khác currency thì ném lỗi.</li>
 *   <li>Chuẩn hoá scale về 4 ngay lúc tạo, nên {@code 10.5} và {@code 10.5000}
 *       so sánh bằng nhau. Với {@code BigDecimal.equals()} thì hai giá trị đó
 *       KHÁC nhau — đây là lỗi kinh điển khi so sánh tiền.</li>
 * </ul>
 *
 * <p>Tuyệt đối không dùng {@code double}/{@code float} cho tiền:
 * {@code 0.1 + 0.2 == 0.30000000000000004} do IEEE-754 không biểu diễn
 * chính xác phân số thập phân.
 */
public record Money(BigDecimal amount, String currency) {

    public static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Compact constructor — chạy trước khi gán field, dùng để validate và chuẩn hoá. */
    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        if (currency.length() != 3) {
            throw new IllegalArgumentException(
                    "currency must be a 3-letter ISO 4217 code, got: " + currency);
        }
        currency = currency.toUpperCase();
        amount = amount.setScale(SCALE, ROUNDING);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money multiply(int quantity) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(quantity)), this.currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: %s vs %s".formatted(this.currency, other.currency));
        }
    }

    @Override
    public String toString() {
        return "%s %s".formatted(amount.toPlainString(), currency);
    }
}
