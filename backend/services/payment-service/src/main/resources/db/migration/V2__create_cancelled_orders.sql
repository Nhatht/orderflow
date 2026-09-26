-- =============================================================================
-- V2: Sổ đơn đã huỷ — "bia mộ" (tombstone) để KHÔNG thu tiền đơn đã huỷ
--
-- Vấn đề (review 25/09): saga timeout huỷ đơn sau 3 phút chờ tiền. Nếu lúc đó
-- payment-service đang sập hoặc tụt lại, payment.requested vẫn nằm trong Kafka.
-- Payment sống lại, xử lý lệnh cũ và TRỪ TIỀN cho một đơn đã huỷ, hàng đã nhả.
--
-- Hai topic khác nhau (payment.requested, order.cancelled) không có thứ tự
-- với nhau, nên lệnh huỷ có thể tới TRƯỚC lệnh thu tiền. Vì vậy phải GHI LẠI
-- việc huỷ, không chỉ phản ứng lúc nó tới: lệnh thu tiền đến sau tra bảng này
-- trước khi gọi cổng.
--
-- Ghi mọi lý do huỷ, không chỉ PAYMENT_TIMEOUT: luật đơn giản "đơn đã huỷ thì
-- không bao giờ thu tiền" dễ đúng hơn một danh sách lý do phải nhớ cập nhật.
-- =============================================================================

CREATE TABLE cancelled_orders (
    order_id        UUID            PRIMARY KEY,   -- một đơn huỷ một lần — INSERT ... ON CONFLICT là idempotent
    reason          VARCHAR(32)     NOT NULL,
    cancelled_at    TIMESTAMPTZ     NOT NULL DEFAULT now()
);
