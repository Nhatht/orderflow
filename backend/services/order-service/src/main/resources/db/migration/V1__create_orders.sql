-- =============================================================================
-- V1: Bảng đơn hàng
--
-- LƯU Ý VỀ KIỂU TIỀN: total_amount và unit_price dùng NUMERIC(19,4).
-- NUMERIC lưu số thập phân CHÍNH XÁC. Nếu dùng DOUBLE PRECISION hay REAL,
-- 0.1 + 0.2 sẽ ra 0.30000000000000004 — cộng dồn qua hàng nghìn đơn là lệch sổ.
-- Bên Java tương ứng là BigDecimal, không bao giờ dùng double/float.
--
-- scale 4 (không phải 2) để chịu được đơn giá lẻ và tỉ giá khi tính toán
-- trung gian, chỉ làm tròn về 2 khi hiển thị.
-- =============================================================================

CREATE TABLE orders (
    id              UUID            PRIMARY KEY,
    customer_id     UUID            NOT NULL,
    status          VARCHAR(32)     NOT NULL,
    total_amount    NUMERIC(19, 4)  NOT NULL,
    currency        VARCHAR(3)      NOT NULL,

    -- Optimistic locking: Hibernate tự tăng cột này mỗi lần UPDATE.
    -- Hai transaction cùng sửa một đơn thì đứa sau nhận
    -- OptimisticLockException thay vì lặng lẽ ghi đè (lost update).
    version         BIGINT          NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT chk_orders_total_non_negative CHECK (total_amount >= 0),
    CONSTRAINT chk_orders_status CHECK (
        status IN ('PENDING', 'STOCK_RESERVED', 'PAID', 'CONFIRMED', 'CANCELLED')
    )
);

CREATE TABLE order_items (
    id              UUID            PRIMARY KEY,
    order_id        UUID            NOT NULL,
    product_id      UUID            NOT NULL,
    product_name    VARCHAR(255)    NOT NULL,
    quantity        INTEGER         NOT NULL,
    unit_price      NUMERIC(19, 4)  NOT NULL,
    currency        VARCHAR(3)      NOT NULL,

    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT chk_order_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_order_items_price_non_negative CHECK (unit_price >= 0)
);

-- Truy vấn hay dùng: "lấy đơn của khách X, mới nhất trước"
CREATE INDEX idx_orders_customer_created
    ON orders (customer_id, created_at DESC);

-- Dùng cho job đối soát: "tìm đơn kẹt ở PENDING quá lâu"
CREATE INDEX idx_orders_status_created
    ON orders (status, created_at);

CREATE INDEX idx_order_items_order
    ON order_items (order_id);
