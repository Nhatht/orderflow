package com.orderflow.inventory.adapter.in.messaging;

import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.order.OrderCreatedEvent;
import com.orderflow.inventory.application.dto.ReserveOrderStockCommand;
import com.orderflow.inventory.application.port.in.ReserveOrderStockUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * DRIVING ADAPTER cho Kafka — vai trò y hệt {@code InventoryController} cho
 * HTTP: nhận dữ liệu từ bên ngoài, dịch thành command, gọi use case. Không
 * chứa logic nghiệp vụ.
 *
 * <p><b>Hợp đồng lỗi với Kafka — phần quan trọng nhất của class này:</b>
 * <ul>
 *   <li>Method trả về bình thường → Spring Kafka commit offset → message
 *       coi như xong, không bao giờ được giao lại.</li>
 *   <li>Method ném exception → offset KHÔNG được commit → error handler thử
 *       lại message đó.</li>
 * </ul>
 * Vì vậy: hết hàng KHÔNG ném exception (use case trả {@code Rejected}, đã phát
 * event, xong việc). Chỉ lỗi hạ tầng — database sập, không lấy được khoá —
 * mới để exception bay ra, vì thử lại sau vài giây có thể thành công.
 * Ném exception cho hết hàng thì message bị thử lại vô ích và chặn các
 * message sau nó trong cùng partition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedListener {

    private final ReserveOrderStockUseCase reserveOrderStock;

    /**
     * Tham số là {@code EventEnvelope<OrderCreatedEvent>} — generic đầy đủ.
     * {@code StringJsonMessageConverter} đọc kiểu này từ chữ ký method và
     * deserialize thẳng ra payload có kiểu. Xem {@code KafkaConfig}.
     */
    @KafkaListener(topics = Topics.ORDER_CREATED)
    public void onOrderCreated(EventEnvelope<OrderCreatedEvent> envelope) {
        // correlationId vào MDC để MỌI dòng log trong lúc xử lý đều mang nó —
        // grep một id là thấy cả hành trình của đơn. Tuần 8 tracing làm việc
        // này tự động; giờ làm tay để hiểu nó giải quyết gì.
        MDC.put("correlationId", envelope.correlationId());
        try {
            OrderCreatedEvent event = envelope.payload();
            log.info("Received {} eventId={} order={} lines={}",
                    envelope.eventType(), envelope.eventId(), event.orderId(), event.items().size());

            var command = new ReserveOrderStockCommand(
                    envelope.eventId(),          // eventId làm khoá idempotency
                    envelope.correlationId(),    // mang tiếp, KHÔNG tạo mới
                    event.orderId(),
                    event.items().stream()
                            .map(i -> new ReserveOrderStockCommand.Item(i.productId(), i.quantity()))
                            .toList());

            reserveOrderStock.reserveForOrder(command);
        } finally {
            MDC.remove("correlationId");   // thread của consumer được tái sử dụng — không dọn là rò sang message sau
        }
    }
}
