package com.orderflow.order.domain;

import com.orderflow.order.domain.model.OrderSaga;
import com.orderflow.order.domain.model.OrderSaga.Outcome;
import com.orderflow.order.domain.model.OrderSaga.Step;
import com.orderflow.order.domain.model.SagaStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Luật chuyển trạng thái của saga — domain thuần, không Spring. */
class OrderSagaTest {

    private final OrderSaga saga = OrderSaga.start(UUID.randomUUID());

    @Test
    @DisplayName("Bắt đầu ở STARTED, bước đầu là xin giữ hàng")
    void startsByRequestingStock() {
        assertThat(saga.status()).isEqualTo(SagaStatus.STARTED);
        assertThat(saga.currentStep()).isEqualTo(Step.RESERVE_STOCK);
        assertThat(saga.pendingLog()).containsExactly(
                new OrderSaga.LogEntry(Step.RESERVE_STOCK, Outcome.REQUESTED, null));
    }

    @Test
    @DisplayName("Luồng thành công: giữ hàng → thu tiền → COMPLETED")
    void happyPath() {
        saga.stockReserved();
        assertThat(saga.status()).isEqualTo(SagaStatus.AWAITING_PAYMENT);

        saga.paymentCompleted();
        assertThat(saga.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(saga.status().isFinal()).isTrue();
    }

    @Test
    @DisplayName("Thanh toán thất bại → COMPENSATING, rồi hàng nhả xong → COMPENSATED")
    void paymentFailureCompensates() {
        saga.stockReserved();
        saga.paymentFailed("CARD_DECLINED");
        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(saga.currentStep()).isEqualTo(Step.RELEASE_STOCK);
        assertThat(saga.status().isFinal()).as("chưa xong — đang chờ inventory nhả hàng").isFalse();

        saga.stockReleased();
        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.failureReason()).isEqualTo("CARD_DECLINED");
    }

    @Test
    @DisplayName("Hết hàng → FAILED ngay, không có bước đền bù nào")
    void stockUnavailableFailsWithoutCompensation() {
        saga.stockUnavailable("INSUFFICIENT_STOCK");

        assertThat(saga.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(saga.pendingLog()).extracting(OrderSaga.LogEntry::step)
                .doesNotContain(Step.RELEASE_STOCK);
    }

    @Test
    @DisplayName("Phản hồi sai thứ tự bị từ chối — thanh toán xong khi chưa giữ hàng là vô lý")
    void outOfOrderRepliesAreRejected() {
        assertThatThrownBy(saga::paymentCompleted).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(saga::stockReleased).isInstanceOf(IllegalStateException.class);

        saga.stockUnavailable("INSUFFICIENT_STOCK");
        assertThatThrownBy(saga::stockReserved)
                .as("saga đã kết thúc thì không đi tiếp được nữa")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Nhật ký ghi đủ từng bước theo đúng thứ tự — dữ liệu cho saga timeline")
    void logRecordsEveryStepInOrder() {
        saga.stockReserved();
        saga.paymentFailed("CARD_DECLINED");
        saga.stockReleased();

        assertThat(saga.pendingLog()).extracting(e -> e.step() + ":" + e.outcome()).containsExactly(
                "RESERVE_STOCK:REQUESTED",
                "RESERVE_STOCK:SUCCEEDED",
                "PROCESS_PAYMENT:REQUESTED",
                "PROCESS_PAYMENT:FAILED",
                "RELEASE_STOCK:REQUESTED",
                "RELEASE_STOCK:SUCCEEDED");
    }
}
