-- =============================================================================
-- Tuần 8: nối distributed trace xuyên qua Outbox
--
-- Request HTTP chỉ GHI dòng outbox; poller gửi nó lên Kafka sau đó, trên thread
-- @Scheduled không có ngữ cảnh trace → Jaeger thấy hai trace rời nhau. Lưu
-- header W3C "traceparent" của request vào đây, poller mở span con của đúng
-- trace đó khi gửi. Xem adapter/out/messaging/OutboxTracing.java.
--
-- NULL khi không có trace (tracing tắt, hoặc event do job tự phát không có
-- request gốc) — poller gửi như cũ.
--
-- Dạng: 00-<traceId 32 hex>-<spanId 16 hex>-<flags 2 hex> = 55 ký tự.
-- =============================================================================

ALTER TABLE outbox ADD COLUMN trace_parent VARCHAR(64) NULL;
