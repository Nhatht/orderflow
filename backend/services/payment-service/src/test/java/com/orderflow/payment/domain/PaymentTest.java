package com.orderflow.payment.domain;

import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Domain thuần — không bật Spring, chạy trong mili giây. */
class PaymentTest {

    private static Payment pending(String amount) {
        return Payment.initiate(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount), "vnd");
    }

    @Test
    @DisplayName("Tạo mới luôn ở PENDING, chuẩn hoá scale 4 và mã tiền tệ viết hoa")
    void initiateStartsPending() {
        Payment p = pending("45000");

        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.amount()).isEqualTo(new BigDecimal("45000.0000"));
        assertThat(p.currency()).isEqualTo("VND");
    }

    @Test
    @DisplayName("Số tiền phải dương")
    void amountMustBePositive() {
        assertThatThrownBy(() -> pending("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending("-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("PENDING → COMPLETED ghi lại mã giao dịch của cổng")
    void completeRecordsGatewayReference() {
        Payment p = pending("45000");
        p.complete("SIM-ABC");

        assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(p.gatewayReference()).isEqualTo("SIM-ABC");
    }

    @Test
    @DisplayName("Đã có kết quả thì không đổi được — đã thu tiền không tự thành 'chưa thu'")
    void finalStateIsImmutable() {
        Payment completed = pending("45000");
        completed.complete("SIM-ABC");
        assertThatThrownBy(() -> completed.fail("late decline")).isInstanceOf(IllegalStateException.class);

        Payment failed = pending("45099");
        failed.fail("CARD_DECLINED");
        assertThatThrownBy(() -> failed.complete("SIM-XYZ")).isInstanceOf(IllegalStateException.class);
    }
}
