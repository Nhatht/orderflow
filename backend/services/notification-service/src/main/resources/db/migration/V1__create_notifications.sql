-- =============================================================================
-- V1: Sổ thông báo — idempotent consumer cho một tác dụng phụ BÊN NGOÀI (email)
--
-- Không dùng bảng processed_events như các service khác, vì bài toán giống
-- payment hơn là inventory: việc chính (gửi mail) xảy ra ở máy chủ SMTP, NGOÀI
-- mọi transaction của mình. Ghi "đã xử lý" rồi mới gửi → chết giữa chừng là
-- MẤT mail. Gửi rồi mới ghi → chết giữa chừng là mail TRÙNG. Không có cách nào
-- chọn được "đúng một lần" khi đầu bên kia không hỗ trợ idempotency key.
--
-- Cách làm ở đây (giống payment, tuần 6):
--   1. INSERT PENDING ... ON CONFLICT DO NOTHING    ← ghi ý định
--   2. đã SENT?  → bỏ qua                            ← chặn trùng gần như mọi trường hợp
--   3. gửi mail, Message-ID = <eventId@orderflow>     ← gửi lại vẫn cùng Message-ID
--   4. UPDATE SENT
-- Khe hở còn lại: chết đúng giữa bước 3 và 4 → gửi lại một lần. Message-ID cố
-- định giúp nhiều mail client tự gộp hai bản làm một. Chọn at-least-once vì
-- với thư xác nhận đơn hàng, trùng một thư còn đỡ hơn mất thư.
-- =============================================================================

CREATE TABLE notifications (
    event_id        UUID            PRIMARY KEY,   -- idempotency key = eventId
    order_id        UUID            NOT NULL,
    kind            VARCHAR(32)     NOT NULL,
    recipient       VARCHAR(255)    NOT NULL,
    status          VARCHAR(16)     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ     NULL,

    CONSTRAINT chk_notifications_status CHECK (status IN ('PENDING', 'SENT'))
);

CREATE INDEX idx_notifications_order ON notifications (order_id);
-- Job theo dõi (sau này): thư nào kẹt PENDING quá lâu?
CREATE INDEX idx_notifications_pending ON notifications (created_at) WHERE status = 'PENDING';
