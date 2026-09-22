package com.orderflow.order.domain.exception;

import com.orderflow.order.domain.model.OrderStatus;

import java.util.UUID;

/**
 * Cố chuyển đơn sang trạng thái không hợp lệ.
 *
 * <p>Ví dụ: đơn đã CANCELLED mà nhận event PaymentCompleted đến muộn.
 * Tình huống này XẢY RA THẬT trong hệ phân tán (late response), nên phải
 * xử lý đàng hoàng chứ không phải coi là bug.
 */
public class InvalidOrderStateException extends RuntimeException {

    private final UUID orderId;
    private final OrderStatus current;
    private final OrderStatus target;

    public InvalidOrderStateException(UUID orderId, OrderStatus current, OrderStatus target) {
        super("Order %s cannot transition from %s to %s".formatted(orderId, current, target));
        this.orderId = orderId;
        this.current = current;
        this.target = target;
    }

    public UUID orderId()          { return orderId; }
    public OrderStatus current()   { return current; }
    public OrderStatus target()    { return target; }
}
