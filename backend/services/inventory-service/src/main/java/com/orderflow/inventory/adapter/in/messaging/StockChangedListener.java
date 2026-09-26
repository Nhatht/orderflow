package com.orderflow.inventory.adapter.in.messaging;

import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.inventory.StockChangedEvent;
import com.orderflow.inventory.application.port.out.StockCachePort;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * INVALIDATION QUA EVENT: tồn kho đổi → xoá cache của sản phẩm đó.
 *
 * <p><b>Vì sao inventory tự nghe event của chính mình thay vì xoá cache ngay sau
 * khi commit?</b> "Commit DB rồi xoá Redis" là DUAL-WRITE — đúng bài toán của
 * tuần 5. App chết giữa hai bước, hoặc Redis chập đúng lúc xoá → cache giữ số
 * cũ tới hết TTL mà không ai biết. Event {@code stock.changed} được ghi vào
 * outbox CÙNG transaction với thay đổi tồn kho, nên việc xoá được bảo đảm
 * at-least-once: Redis lỗi thì listener ném, Kafka thử lại có giãn cách, cuối
 * cùng vào DLT chứ không mất.
 *
 * <p>Idempotent tự nhiên: xoá một key hai lần vẫn là key không tồn tại.
 *
 * <p>Gọi thẳng port ra (không qua use case) — không có quyết định nghiệp vụ nào
 * ở đây, chỉ là dọn cache; một class use case chỉ để chuyển tiếp là thừa.
 */
@Component
@RequiredArgsConstructor
public class StockChangedListener {

    private final StockCachePort cache;

    @KafkaListener(topics = Topics.STOCK_CHANGED)
    public void onStockChanged(EventEnvelope<StockChangedEvent> envelope) {
        cache.evict(envelope.payload().productId());
    }
}
