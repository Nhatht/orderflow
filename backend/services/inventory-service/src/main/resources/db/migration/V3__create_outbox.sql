-- =============================================================================
-- V3: TRANSACTIONAL OUTBOX — cùng cấu trúc với order-service (V2__create_outbox.sql,
-- xem giải thích đầy đủ ở đó).
--
-- Ở inventory, outbox còn vá một lỗ hổng TỆ HƠN bên order. Tuần 4:
--
--   BEGIN
--     ghi processed_events(eventId)   ← "đã xử lý"
--     giữ hàng
--   COMMIT
--   gửi stock.reserved lên Kafka       ← app chết ở đây
--
-- Kafka giao lại order.created → processed_events nói "đã xử lý" → bỏ qua.
-- Kết quả giữ hàng MẤT VĨNH VIỄN, và chính cơ chế idempotency chặn mọi cơ
-- hội tự phục hồi.
--
-- Từ tuần 5, sổ, việc giữ hàng và dòng outbox cùng commit trong MỘT
-- transaction: đã ghi "đã xử lý" thì chắc chắn đã có event chờ gửi.
-- =============================================================================

CREATE TABLE outbox (
    id              UUID            PRIMARY KEY,   -- = eventId
    seq             BIGSERIAL       NOT NULL,      -- thứ tự gửi
    aggregate_type  VARCHAR(64)     NOT NULL,
    aggregate_id    VARCHAR(64)     NOT NULL,      -- Kafka key (orderId)
    event_type      VARCHAR(64)     NOT NULL,
    topic           VARCHAR(128)    NOT NULL,
    payload         JSONB           NOT NULL,      -- EventEnvelope đã serialize
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ     NULL
);

CREATE INDEX idx_outbox_unpublished ON outbox (seq) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_published_at ON outbox (published_at) WHERE published_at IS NOT NULL;
