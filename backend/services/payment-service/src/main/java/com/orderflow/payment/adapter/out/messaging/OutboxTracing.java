package com.orderflow.payment.adapter.out.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * NỐI TRACE XUYÊN QUA OUTBOX (pattern 7 gặp pattern 2).
 *
 * <p><b>Vấn đề:</b> tracing tự động truyền trace qua HTTP và Kafka bằng header
 * W3C {@code traceparent}. Nhưng Outbox chen một bảng database vào giữa: request
 * HTTP chỉ GHI dòng outbox rồi trả về; {@link OutboxPoller} gửi dòng đó lên Kafka
 * nửa giây sau, trên thread {@code @Scheduled} không có ngữ cảnh trace nào. Kết
 * quả trên Jaeger: HAI trace rời nhau — "POST /api/orders" dừng ở order-service,
 * và một trace khác bắt đầu từ poller. Mất đúng thứ tracing sinh ra để làm.
 *
 * <p><b>Cách sửa:</b> ghi {@code traceparent} của request vào cột
 * {@code outbox.trace_parent} CÙNG lúc ghi event; poller đọc lại, mở một span con
 * của đúng trace đó rồi mới gửi — KafkaTemplate (observation bật) gắn header
 * {@code traceparent} của span con vào message, và consumer bên kia nối tiếp.
 * Debezium Outbox Router làm cùng việc này với cột {@code tracingspancontext}.
 *
 * <p>Tracing tắt (test mặc định tắt) → không có bean {@link Tracer} → dùng NOOP:
 * cột để NULL, poller gửi như cũ.
 */
@Component
public class OutboxTracing {

    static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    /** {@code traceparent} của span đang chạy, hoặc {@code null} nếu không có trace. */
    public String currentTraceParent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /**
     * Message Kafka cho một dòng outbox, LUÔN mang {@code traceparent} đã lưu.
     *
     * <p>Khi tracing bật, KafkaTemplate (observation) gắn thêm {@code traceparent}
     * của span con {@code outbox relay}; consumer đọc header CUỐI CÙNG nên thấy
     * span con. Khi poller chạy không có tracing (bị tắt, hoặc instance cấu hình
     * khác), header đã lưu vẫn đi theo — trace không đứt vì một instance lệch cấu hình.
     */
    public ProducerRecord<String, String> record(String topic, String key, String value, String traceParent) {
        var record = new ProducerRecord<String, String>(topic, key, value);
        if (traceParent != null) {
            record.headers().add(TRACEPARENT, traceParent.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    /**
     * Chạy {@code work} trong một span con của trace đã lưu — mọi thứ nó làm
     * (gửi Kafka) nằm trong trace gốc của request.
     */
    public <T> T inStoredTrace(String traceParent, String spanName, Supplier<T> work) {
        if (traceParent == null) {
            return work.get();
        }
        Span span = propagator.extract(Map.of(TRACEPARENT, traceParent), Map::get)
                .name(spanName)
                .kind(Span.Kind.PRODUCER)
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return work.get();
        } finally {
            span.end();
        }
    }
}
