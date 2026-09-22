-- =============================================================================
-- V1: Kho hàng và phiếu giữ hàng
--
-- THIẾT KẾ CỐT LÕI — SEMANTIC LOCK:
-- Tách tồn kho thành HAI cột thay vì một con số duy nhất:
--
--   available_qty  hàng còn bán được
--   reserved_qty   hàng đang bị giữ bởi đơn chưa chốt
--
-- Vì sao không chỉ dùng một cột `quantity`? Vì khi đơn A giữ hàng, ta cần
-- phân biệt được "hết hàng thật" với "đang có người giữ". Một con số 0
-- trơ trọi không nói được điều đó, nên hệ thống buộc phải nói dối khách B
-- là "hết hàng" — rồi 3 giây sau đơn A fail và hàng có lại.
--
-- Có hai cột thì hiển thị được "còn 1, đang có người đặt, thử lại sau 2 phút",
-- và job dọn dẹp tự nhả được phiếu quá hạn.
--
-- Xem docs/PATTERNS.md mục 0, countermeasure số 3.
-- =============================================================================

CREATE TABLE products (
    id          UUID            PRIMARY KEY,
    sku         VARCHAR(64)     NOT NULL UNIQUE,
    name        VARCHAR(255)    NOT NULL,
    price       NUMERIC(19, 4)  NOT NULL,
    currency    VARCHAR(3)      NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT chk_products_price_non_negative CHECK (price >= 0)
);

CREATE TABLE stock (
    product_id      UUID        PRIMARY KEY,
    available_qty   INTEGER     NOT NULL,
    reserved_qty    INTEGER     NOT NULL DEFAULT 0,

    -- Optimistic locking — LỚP BẢO VỆ THỨ HAI, sau Redis lock.
    -- Redis lock có thể hết hạn sớm hoặc Redis mất kết nối; khi đó cột này
    -- vẫn chặn được lost update ở tầng database.
    -- Nguyên tắc: không bao giờ dựa vào một lớp phòng thủ duy nhất.
    version         BIGINT      NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_stock_product
        FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,

    -- Chốt chặn cuối cùng: dù logic Java có sai thế nào, database cũng
    -- KHÔNG cho phép tồn kho âm. Đây là thứ duy nhất không thể bị bug
    -- ở tầng ứng dụng vượt qua.
    CONSTRAINT chk_stock_available_non_negative CHECK (available_qty >= 0),
    CONSTRAINT chk_stock_reserved_non_negative  CHECK (reserved_qty  >= 0)
);

CREATE TABLE stock_reservations (
    id          UUID            PRIMARY KEY,
    order_id    UUID            NOT NULL,
    product_id  UUID            NOT NULL,
    quantity    INTEGER         NOT NULL,
    status      VARCHAR(16)     NOT NULL,

    -- TIMEBOUND: phiếu giữ hàng có hạn. Quá hạn mà đơn chưa chốt thì
    -- job dọn dẹp tự nhả hàng về kho.
    --
    -- CẢNH BÁO khi chọn giá trị: đặt quá ngắn thì huỷ nhầm đơn hợp lệ
    -- (khách đang nhập OTP của VNPay mất 30-90 giây). Phải đặt theo
    -- p99 thời gian thật của bước thanh toán, thường là 2-5 phút.
    -- Xem docs/PATTERNS.md, bẫy "late response".
    expires_at  TIMESTAMPTZ     NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT fk_reservations_product
        FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_reservations_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_reservations_status CHECK (
        status IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED')
    ),

    -- IDEMPOTENCY Ở TẦNG DATABASE.
    -- Kafka là at-least-once: event OrderCreated CÓ THỂ đến hai lần.
    -- Ràng buộc này khiến lần thứ hai bị database từ chối, nên không thể
    -- giữ hàng trùng dù code có retry bao nhiêu lần.
    -- Đặt ràng buộc ở database thay vì chỉ kiểm tra trong Java là có chủ đích:
    -- kiểm tra trong Java có khoảng hở giữa lúc đọc và lúc ghi.
    CONSTRAINT uq_reservations_order_product UNIQUE (order_id, product_id)
);

-- Dùng cho job dọn phiếu quá hạn: "tìm phiếu HELD đã hết hạn"
CREATE INDEX idx_reservations_status_expires
    ON stock_reservations (status, expires_at)
    WHERE status = 'HELD';

CREATE INDEX idx_reservations_order
    ON stock_reservations (order_id);

-- =============================================================================
-- Dữ liệu mẫu để test và chạy thử
-- =============================================================================
INSERT INTO products (id, sku, name, price, currency) VALUES
    ('11111111-1111-1111-1111-111111111111', 'KDT-001', 'Kẹo dừa Bến Tre',     45000.0000, 'VND'),
    ('22222222-2222-2222-2222-222222222222', 'BTN-001', 'Bánh tráng Tây Ninh', 25500.5000, 'VND'),
    ('33333333-3333-3333-3333-333333333333', 'LAST-01', 'Sản phẩm cuối cùng',  99000.0000, 'VND');

INSERT INTO stock (product_id, available_qty, reserved_qty) VALUES
    ('11111111-1111-1111-1111-111111111111', 100, 0),
    ('22222222-2222-2222-2222-222222222222',  50, 0),
    -- Sản phẩm chỉ còn ĐÚNG 1 — dùng cho test chống oversell
    ('33333333-3333-3333-3333-333333333333',   1, 0);
