package com.orderflow.inventory.integration;

import com.orderflow.inventory.application.dto.ReserveStockCommand;
import com.orderflow.inventory.application.port.in.GetStockQuery;
import com.orderflow.inventory.application.port.in.ReserveStockUseCase;
import com.orderflow.inventory.domain.exception.InsufficientStockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TIÊU CHÍ NGHIỆM THU CỦA TUẦN 3 — chứng minh chống oversell.
 *
 * <p>Test này chạy trên <b>PostgreSQL và Redis THẬT</b> trong Docker qua
 * Testcontainers. Không mock, không H2, không embedded Redis. Lý do: thứ đang
 * được kiểm chứng ở đây là hành vi đồng thời của distributed lock và optimistic
 * locking — mock sẽ chỉ kiểm chứng chính cái mock.
 *
 * <p><b>Kịch bản chính:</b> sản phẩm còn ĐÚNG 1 cái, bắn {@code N} luồng cùng
 * lúc cùng đòi mua. Kết quả bắt buộc: đúng 1 luồng thành công, N−1 luồng nhận
 * {@code InsufficientStockException}, và {@code available_qty} không bao giờ âm.
 *
 * <p>Bỏ Redis lock đi rồi chạy lại thì test này FAIL — đó là cách chứng minh
 * cái khoá thật sự có tác dụng chứ không phải "có cho đẹp".
 *
 * <p><b>Ghi chú về thiết kế test:</b> container dùng chung (xem
 * {@link AbstractInventoryIT}), database KHÔNG được reset giữa các test. Vì vậy
 * mỗi test tự tạo sản phẩm riêng thay vì dùng dữ liệu seed — nếu không, test
 * này giữ hàng của test kia và kết quả phụ thuộc thứ tự chạy.
 *
 * <p>Tuần 3 test này tắt hẳn Kafka. Từ tuần 4 service có Kafka listener và
 * publisher — context không dựng được nếu thiếu Kafka — nên dùng chung nền với
 * {@link OrderCreatedConsumerIT}.
 */
class ConcurrentReservationIT extends AbstractInventoryIT {

    /** Số khách cùng tranh nhau chiếc cuối cùng. */
    private static final int CONCURRENT_BUYERS = 50;

    @Autowired ReserveStockUseCase reserveStock;
    @Autowired GetStockQuery getStock;

    @Test
    @DisplayName("50 luồng cùng mua sản phẩm còn 1 cái → đúng 1 thành công, tồn kho không âm")
    void preventsOversellUnderConcurrentLoad() throws Exception {
        UUID scarceProduct = givenProductWithStock(1);

        var successes = new AtomicInteger();
        var insufficientStock = new AtomicInteger();
        var otherFailures = new AtomicInteger();

        // CountDownLatch để mọi luồng xuất phát CÙNG MỘT LÚC.
        // Không có nó, các luồng khởi động lệch nhau vài mili giây và gần như
        // không tranh chấp — test sẽ xanh cả khi code sai.
        var startGate = new CountDownLatch(1);
        var finishGate = new CountDownLatch(CONCURRENT_BUYERS);

        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_BUYERS)) {
            for (int i = 0; i < CONCURRENT_BUYERS; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();   // chờ hiệu lệnh
                        reserveStock.reserve(
                                new ReserveStockCommand(UUID.randomUUID(), scarceProduct, 1));
                        successes.incrementAndGet();

                    } catch (InsufficientStockException e) {
                        insufficientStock.incrementAndGet();   // kết quả MONG ĐỢI cho N-1 luồng

                    } catch (Exception e) {
                        otherFailures.incrementAndGet();

                    } finally {
                        finishGate.countDown();
                    }
                });
            }

            startGate.countDown();   // bắn!
            assertThat(finishGate.await(60, TimeUnit.SECONDS))
                    .as("tất cả luồng phải kết thúc trong 60 giây")
                    .isTrue();
        }

        var stock = stockInDb(scarceProduct);

        assertThat(successes.get())
                .as("CHỈ một luồng được giữ hàng")
                .isEqualTo(1);

        assertThat(insufficientStock.get())
                .as("các luồng còn lại phải nhận InsufficientStockException")
                .isEqualTo(CONCURRENT_BUYERS - 1);

        assertThat(otherFailures.get())
                .as("không được có lỗi ngoài dự kiến (lock timeout, optimistic lock...)")
                .isZero();

        assertThat(stock.availableQty())
                .as("TỒN KHO KHÔNG BAO GIỜ ĐƯỢC ÂM — bất biến quan trọng nhất")
                .isZero();

        assertThat(stock.reservedQty())
                .as("đúng 1 cái đang bị giữ")
                .isEqualTo(1);

        assertThat(stock.totalQty())
                .as("giữ hàng không làm mất hàng, chỉ chuyển cột")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("gọi lại cùng (orderId, productId) không giữ hàng hai lần — idempotent")
    void reservationIsIdempotent() {
        UUID product = givenProductWithStock(100);
        UUID orderId = UUID.randomUUID();
        var command = new ReserveStockCommand(orderId, product, 5);

        var first = reserveStock.reserve(command);
        var second = reserveStock.reserve(command);   // event Kafka đến lần hai
        var third = reserveStock.reserve(command);    // và lần ba

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(third.id()).isEqualTo(first.id());

        var stock = stockInDb(product);
        assertThat(stock.availableQty())
                .as("chỉ trừ MỘT lần dù gọi ba lần")
                .isEqualTo(95);
        assertThat(stock.reservedQty()).isEqualTo(5);
    }

    @Test
    @DisplayName("nhả hàng trả đúng số lượng về kho, và gọi nhiều lần vẫn an toàn")
    void releaseIsIdempotent() {
        UUID product = givenProductWithStock(50);
        UUID orderId = UUID.randomUUID();

        reserveStock.reserve(new ReserveStockCommand(orderId, product, 4));
        assertThat(stockInDb(product).availableQty()).isEqualTo(46);

        reserveStock.release(orderId, product);
        reserveStock.release(orderId, product);   // saga retry sau khi restart
        reserveStock.release(orderId, product);

        var stock = stockInDb(product);
        assertThat(stock.availableQty())
                .as("chỉ nhả MỘT lần dù gọi ba lần")
                .isEqualTo(50);
        assertThat(stock.reservedQty()).isZero();
    }

    @Test
    @DisplayName("chốt đơn thì hàng rời kho: reserved giảm, available không tăng")
    void confirmRemovesStockPermanently() {
        UUID product = givenProductWithStock(20);
        UUID orderId = UUID.randomUUID();

        reserveStock.reserve(new ReserveStockCommand(orderId, product, 3));
        reserveStock.confirm(orderId, product);
        reserveStock.confirm(orderId, product);   // idempotent

        var stock = stockInDb(product);
        assertThat(stock.availableQty()).isEqualTo(17);
        assertThat(stock.reservedQty()).isZero();
        assertThat(stock.totalQty())
                .as("đây là lúc DUY NHẤT tổng tồn kho giảm")
                .isEqualTo(17);
    }

    @Test
    @DisplayName("nhiều sản phẩm khác nhau không chặn nhau — khoá theo từng sản phẩm")
    void differentProductsDoNotBlockEachOther() throws Exception {
        List<UUID> products = List.of(givenProductWithStock(10), givenProductWithStock(10));

        var successes = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var futures = products.stream()
                    .map(p -> pool.submit(() -> {
                        reserveStock.reserve(new ReserveStockCommand(UUID.randomUUID(), p, 1));
                        successes.incrementAndGet();
                        return null;
                    }))
                    .toList();

            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(successes.get())
                .as("mua sản phẩm A và mua sản phẩm B phải chạy song song được")
                .isEqualTo(2);
    }
}
