package com.orderflow.inventory.adapter.in.scheduling;

import com.orderflow.inventory.application.port.in.ExpireReservationsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * DRIVING ADAPTER dạng hẹn giờ — vai trò giống controller hay Kafka listener:
 * một nguồn kích hoạt use case. Đặt ở {@code adapter/in} vì "đồng hồ" cũng là
 * một tác nhân bên ngoài gọi vào hệ thống.
 *
 * <p>Tách job khỏi logic để test gọi thẳng {@link ExpireReservationsUseCase}
 * với một {@code now} tuỳ ý, không phải chờ đồng hồ thật.
 */
@Component
@RequiredArgsConstructor
public class ReservationExpiryJob {

    private final ExpireReservationsUseCase expireReservations;

    /**
     * 30 giây một lần: phiếu sống 3 phút nên nhả trễ tối đa 30 giây là chấp
     * nhận được. Quét dày hơn chỉ tăng tải database mà không đổi gì đáng kể.
     */
    @Scheduled(fixedDelayString = "${orderflow.inventory.expiry.interval:PT30S}")
    public void run() {
        expireReservations.expireDue(Instant.now());
    }
}
