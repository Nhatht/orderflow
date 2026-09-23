-- =============================================================================
-- V2: TRANSACTIONAL OUTBOX (pattern 2 — quan trọng nhất của dự án)
--
-- Vấn đề (đã tái hiện thật ngày 23/09/2026): lưu đơn vào PostgreSQL rồi mới
-- gửi Kafka là HAI thao tác trên HAI hệ thống. App chết ở giữa → đơn nằm
-- PENDING mãi, event không bao giờ tới Kafka, không một dòng log lỗi.
--
-- Giải pháp: KHÔNG gửi Kafka trong luồng xử lý. Ghi event vào bảng này, TRONG
-- CÙNG transaction với đơn hàng. Cùng một database → atomic thật: đơn và event
-- cùng tồn tại hoặc cùng không. Một poller chạy nền đọc bảng này và đẩy lên
-- Kafka sau.
--
-- Kafka sập 10 phút? Event nằm yên ở đây, Kafka sống lại là gửi tiếp.
-- Không mất — chỉ chậm.
-- =============================================================================

CREATE TABLE outbox (
    -- = eventId của EventEnvelope. KHÔNG sinh id riêng.
    -- Poller có thể gửi một dòng NHIỀU LẦN (gửi xong, chết trước khi kịp đánh
    -- dấu published_at). Nhờ id = eventId, mọi bản gửi lại mang cùng eventId,
    -- và idempotent consumer phía nhận (processed_events) tự loại bản trùng.
    -- Outbox cho at-least-once; idempotent consumer biến nó thành effectively-once.
    id              UUID            PRIMARY KEY,

    -- Thứ tự gửi. KHÔNG dùng created_at: now() trả về thời điểm BẮT ĐẦU
    -- transaction, nên nhiều event trong cùng một transaction có created_at
    -- giống hệt nhau và thứ tự giữa chúng không xác định.
    seq             BIGSERIAL       NOT NULL,

    aggregate_type  VARCHAR(64)     NOT NULL,
    aggregate_id    VARCHAR(64)     NOT NULL,   -- dùng làm Kafka key
    event_type      VARCHAR(64)     NOT NULL,
    topic           VARCHAR(128)    NOT NULL,

    -- Toàn bộ EventEnvelope đã serialize — đúng từng byte sẽ lên Kafka.
    -- JSONB thay vì TEXT để truy vấn được khi gỡ lỗi:
    --   SELECT * FROM outbox WHERE payload->'payload'->>'orderId' = '...';
    payload         JSONB           NOT NULL,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ     NULL        -- NULL = chưa gửi
);

-- Index riêng phần CHƯA GỬI (partial index). Bảng outbox chủ yếu chứa dòng
-- đã gửi xong; poller chỉ quan tâm vài dòng mới. Index toàn bảng sẽ phình
-- theo thời gian, index này luôn nhỏ.
CREATE INDEX idx_outbox_unpublished ON outbox (seq) WHERE published_at IS NULL;

-- Cho job dọn dẹp xoá dòng đã gửi quá hạn giữ lại.
CREATE INDEX idx_outbox_published_at ON outbox (published_at) WHERE published_at IS NOT NULL;
