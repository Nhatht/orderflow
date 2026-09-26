package com.orderflow.inventory.domain;

import com.orderflow.inventory.domain.exception.InsufficientStockException;
import com.orderflow.inventory.domain.model.ReservationStatus;
import com.orderflow.inventory.domain.model.Stock;
import com.orderflow.inventory.domain.model.StockReservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Unit test thuần — không Spring, không Redis, không database. Chạy vài mili giây. */
class StockTest {

    private static final UUID PRODUCT = UUID.randomUUID();

    private static Stock stockOf(int available, int reserved) {
        return new Stock(PRODUCT, available, reserved, Instant.now(), 0L);
    }

    @Nested
    @DisplayName("Giữ hàng")
    class Reserving {

        @Test
        @DisplayName("chuyển số lượng từ available sang reserved, tổng không đổi")
        void movesBetweenColumns() {
            Stock stock = stockOf(10, 0);
            stock.reserve(3);

            assertThat(stock.availableQty()).isEqualTo(7);
            assertThat(stock.reservedQty()).isEqualTo(3);
            assertThat(stock.totalQty()).isEqualTo(10);   // hàng CHƯA rời kho
        }

        @Test
        @DisplayName("không đủ hàng thì ném InsufficientStockException")
        void rejectsWhenNotEnough() {
            Stock stock = stockOf(2, 0);

            assertThatThrownBy(() -> stock.reserve(3))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("requested 3, available 2");

            // Quan trọng: thất bại KHÔNG được làm thay đổi trạng thái
            assertThat(stock.availableQty()).isEqualTo(2);
            assertThat(stock.reservedQty()).isZero();
        }

        @Test
        @DisplayName("hàng đang bị giữ không tính là bán được")
        void reservedIsNotAvailable() {
            Stock stock = stockOf(1, 0);
            stock.reserve(1);

            // Đây chính là điều khách B nhìn thấy khi A đang giữ chiếc áo cuối
            assertThat(stock.canReserve(1)).isFalse();
            assertThatThrownBy(() -> stock.reserve(1))
                    .isInstanceOf(InsufficientStockException.class);
        }

        @Test
        @DisplayName("quantity không dương bị từ chối")
        void rejectsNonPositive() {
            Stock stock = stockOf(10, 0);
            assertThatThrownBy(() -> stock.reserve(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> stock.reserve(-1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Nhả hàng — bước compensate")
    class Releasing {

        @Test
        @DisplayName("trả hàng về available, khôi phục nguyên trạng")
        void restoresAvailable() {
            Stock stock = stockOf(10, 0);
            stock.reserve(3);
            stock.release(3);

            assertThat(stock.availableQty()).isEqualTo(10);
            assertThat(stock.reservedQty()).isZero();
        }

        @Test
        @DisplayName("không nhả được nhiều hơn số đang giữ")
        void rejectsOverRelease() {
            Stock stock = stockOf(10, 0);
            stock.reserve(2);

            assertThatThrownBy(() -> stock.release(5))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only 2 reserved");
        }
    }

    @Nested
    @DisplayName("Chốt đơn")
    class Confirming {

        @Test
        @DisplayName("hàng rời kho: reserved giảm, available KHÔNG tăng")
        void stockLeavesWarehouse() {
            Stock stock = stockOf(10, 0);
            stock.reserve(3);
            stock.confirm(3);

            assertThat(stock.availableQty()).isEqualTo(7);
            assertThat(stock.reservedQty()).isZero();
            assertThat(stock.totalQty()).isEqualTo(7);   // tổng GIẢM — lúc duy nhất
        }
    }

    @Nested
    @DisplayName("Phiếu giữ hàng")
    class Reservations {

        @Test
        @DisplayName("tạo mới ở trạng thái HELD và có hạn")
        void startsHeld() {
            var r = StockReservation.hold(UUID.randomUUID(), PRODUCT, 2, Duration.ofMinutes(3));

            assertThat(r.status()).isEqualTo(ReservationStatus.HELD);
            assertThat(r.expiresAt()).isAfter(Instant.now());
            assertThat(r.isExpired(Instant.now())).isFalse();
        }

        @Test
        @DisplayName("quá hạn thì isExpired trả true")
        void detectsExpiry() {
            var r = StockReservation.hold(UUID.randomUUID(), PRODUCT, 1, Duration.ofSeconds(1));
            assertThat(r.isExpired(Instant.now().plusSeconds(2))).isTrue();
        }

        @Test
        @DisplayName("phiếu đã ở trạng thái cuối không đổi được nữa — chặn late response")
        void rejectsChangeAfterFinal() {
            var r = StockReservation.hold(UUID.randomUUID(), PRODUCT, 1, Duration.ofMinutes(3));
            r.release();

            // Tình huống có thật: saga đã compensate và nhả hàng, rồi
            // PaymentCompleted của cổng thanh toán mới về tới và đòi confirm.
            assertThatThrownBy(r::confirm)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already RELEASED");
        }

        @Test
        @DisplayName("phiếu hết hạn không còn active")
        void expiredIsFinal() {
            var r = StockReservation.hold(UUID.randomUUID(), PRODUCT, 1, Duration.ofMinutes(3));
            r.expire();

            assertThat(r.status().isFinal()).isTrue();
            assertThat(r.status().isActive()).isFalse();
        }

        @Test
        @DisplayName("chốt sau khi hết hạn: chỉ đi được từ EXPIRED, không từ RELEASED")
        void confirmAfterExpiryOnlyFromExpired() {
            var expired = StockReservation.hold(UUID.randomUUID(), PRODUCT, 1, Duration.ofMinutes(3));
            expired.expire();
            expired.confirmAfterExpiry();
            assertThat(expired.status()).isEqualTo(ReservationStatus.CONFIRMED);

            // RELEASED = saga đã huỷ đơn. Không có chuyện hồi sinh.
            var released = StockReservation.hold(UUID.randomUUID(), PRODUCT, 1, Duration.ofMinutes(3));
            released.release();
            assertThatThrownBy(released::confirmAfterExpiry).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Chốt đơn khi phiếu đã hết hạn (phương án D)")
    class ConfirmingAfterExpiry {

        @Test
        @DisplayName("còn hàng: lấy lại từ available, tổng tồn giảm")
        void takesBackFromAvailable() {
            Stock stock = stockOf(10, 0);   // hàng đã được job trả về kho
            stock.confirmFromAvailable(4);

            assertThat(stock.availableQty()).isEqualTo(6);
            assertThat(stock.reservedQty()).isZero();
            assertThat(stock.totalQty()).isEqualTo(6);
        }

        @Test
        @DisplayName("hết hàng (đã bán cho người khác): ném InsufficientStockException, không đổi gì")
        void oversoldIsRejected() {
            Stock stock = stockOf(0, 1);   // món duy nhất đã được người khác giữ

            assertThatThrownBy(() -> stock.confirmFromAvailable(1))
                    .isInstanceOf(InsufficientStockException.class);
            assertThat(stock.availableQty()).isZero();
            assertThat(stock.reservedQty()).as("không đụng tới hàng người khác đang giữ").isEqualTo(1);
        }
    }
}
