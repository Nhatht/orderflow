package com.orderflow.payment.adapter.in.web;

import com.orderflow.payment.application.port.out.PaymentRepositoryPort;
import com.orderflow.payment.domain.model.Payment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Chỉ ĐỌC — để xem kết quả khi test tay và gỡ lỗi.
 *
 * <p>Không có endpoint tạo thanh toán: thanh toán chỉ xảy ra khi saga yêu cầu
 * qua {@code payment.requested}. Mở REST để thu tiền thì sẽ có hai đường vào
 * cho cùng một hành động nhạy cảm nhất hệ thống.
 *
 * <p>Gọi thẳng repository port từ controller (không qua query use case) là
 * đơn giản hoá có chủ đích cho endpoint gỡ lỗi chỉ đọc.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Read-only view of payments, for debugging")
public class PaymentController {

    private final PaymentRepositoryPort paymentRepository;

    public record PaymentResponse(UUID id, UUID orderId, BigDecimal amount, String currency,
                                  String status, String failureReason, String gatewayReference,
                                  Instant createdAt, Instant updatedAt) {
        static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.id(), p.orderId(), p.amount(), p.currency(), p.status().name(),
                    p.failureReason(), p.gatewayReference(), p.createdAt(), p.updatedAt());
        }
    }

    @GetMapping("/by-order/{orderId}")
    @Operation(summary = "Get the payment of an order")
    public ResponseEntity<PaymentResponse> byOrder(@PathVariable UUID orderId) {
        return paymentRepository.findByOrderId(orderId)
                .map(PaymentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
