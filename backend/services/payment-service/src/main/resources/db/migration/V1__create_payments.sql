-- =============================================================================
-- V1: Thanh toán + outbox
-- =============================================================================

CREATE TABLE payments (
    -- id ĐỒNG THỜI là idempotency key gửi sang cổng thanh toán.
    -- Sinh MỘT LẦN, commit xuống đây TRƯỚC khi gọi cổng. Mọi lần thử lại sau
    -- đó (Kafka giao lại, app restart giữa chừng) đều gửi đúng key này, nên
    -- cổng nhận ra và trả kết quả cũ thay vì trừ tiền lần nữa.
    id                  UUID            PRIMARY KEY,

    -- Một đơn chỉ được thu tiền MỘT lần — ràng buộc nghiệp vụ, đặt ở database.
    -- Đây là khoá idempotency ở phía NHẬN lệnh: payment.requested có đến bao
    -- nhiêu lần, với eventId khác nhau hay không, cũng chỉ ra một dòng.
    order_id            UUID            NOT NULL UNIQUE,

    customer_id         UUID            NOT NULL,
    amount              NUMERIC(19, 4)  NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    status              VARCHAR(16)     NOT NULL,
    failure_reason      VARCHAR(255)    NULL,
    gateway_reference   VARCHAR(64)     NULL,   -- mã giao dịch phía cổng, để đối soát
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT chk_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_payments_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

-- Job đối soát (sau này): "payment nào kẹt PENDING quá lâu?"
CREATE INDEX idx_payments_status_created ON payments (status, created_at);

-- Transactional Outbox — cùng cấu trúc với order-service, xem giải thích ở đó.
CREATE TABLE outbox (
    id              UUID            PRIMARY KEY,   -- = eventId
    seq             BIGSERIAL       NOT NULL,
    aggregate_type  VARCHAR(64)     NOT NULL,
    aggregate_id    VARCHAR(64)     NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    topic           VARCHAR(128)    NOT NULL,
    payload         JSONB           NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ     NULL
);

CREATE INDEX idx_outbox_unpublished ON outbox (seq) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_published_at ON outbox (published_at) WHERE published_at IS NOT NULL;
