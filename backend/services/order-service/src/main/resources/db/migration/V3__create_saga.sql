-- =============================================================================
-- V3: Trạng thái saga + nhật ký từng bước + sổ idempotent consumer
-- =============================================================================

-- Trạng thái HIỆN TẠI của saga cho mỗi đơn. Orchestrator đọc bảng này để
-- biết "đơn này đang chờ gì", nên sau khi service restart giữa chừng vẫn đi
-- tiếp được — trạng thái nằm ở database, không nằm trong RAM.
--
-- KHÔNG có FOREIGN KEY sang orders — cố ý. Đơn được lưu qua JPA, mà Hibernate
-- chỉ INSERT lúc flush (thường là lúc commit); saga_state được ghi bằng JDBC
-- ngay lập tức. Có FK thì INSERT saga_state chạy TRƯỚC INSERT orders và bị từ
-- chối. Hai cách sửa: ép flush sớm, hoặc bỏ FK. Chọn bỏ FK: hai bảng luôn được
-- ghi trong cùng một transaction bởi cùng một use case, nên FK không bảo vệ
-- thêm được gì mà chỉ buộc code phụ thuộc vào thứ tự flush của Hibernate.
CREATE TABLE saga_state (
    order_id        UUID            PRIMARY KEY,
    status          VARCHAR(20)     NOT NULL,
    current_step    VARCHAR(32)     NOT NULL,
    failure_reason  VARCHAR(64)     NULL,

    -- Optimistic locking viết TAY (không qua @Version của JPA):
    --   UPDATE ... SET version = version + 1 WHERE order_id = ? AND version = ?
    -- 0 dòng bị ảnh hưởng nghĩa là có người sửa trước → ném exception.
    -- Đây chính xác là thứ Hibernate làm sau hậu trường với @Version.
    version         BIGINT          NOT NULL DEFAULT 0,

    started_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT chk_saga_status CHECK (status IN (
        'STARTED', 'AWAITING_PAYMENT', 'COMPLETED', 'COMPENSATING', 'COMPENSATED', 'FAILED'))
);

-- Job theo dõi (sau này): "saga nào kẹt ở một trạng thái chờ quá lâu?"
CREATE INDEX idx_saga_state_status_updated ON saga_state (status, updated_at);

-- Nhật ký CHỈ GHI THÊM (append-only) — mỗi bước saga là một dòng, không bao
-- giờ UPDATE hay DELETE. Đây là nguồn dữ liệu cho trang saga timeline ở
-- frontend (tuần 11): hiển thị từng bước theo thời gian, kể cả bước đền bù.
CREATE TABLE saga_step_log (
    id              BIGSERIAL       PRIMARY KEY,   -- thứ tự tuyệt đối của các bước
    order_id        UUID            NOT NULL,
    step            VARCHAR(32)     NOT NULL,
    outcome         VARCHAR(16)     NOT NULL,
    detail          VARCHAR(255)    NULL,
    -- clock_timestamp() chứ không phải now(): now() đứng yên trong suốt
    -- transaction, nên nhiều bước ghi trong cùng một transaction sẽ trùng
    -- giờ đến từng micro giây. Timeline cần thời điểm thật của từng dòng.
    occurred_at     TIMESTAMPTZ     NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT chk_saga_step_outcome CHECK (outcome IN ('REQUESTED', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_saga_step_log_order ON saga_step_log (order_id, id);

-- Idempotent consumer cho các phản hồi saga — cùng thiết kế với inventory
-- (V2__create_processed_events.sql bên đó).
CREATE TABLE processed_events (
    event_id        UUID            PRIMARY KEY,
    operation       VARCHAR(64)     NOT NULL,
    processed_at    TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
