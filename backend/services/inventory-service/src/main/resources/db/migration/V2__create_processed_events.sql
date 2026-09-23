-- =============================================================================
-- V2: Sổ ghi event đã xử lý — IDEMPOTENT CONSUMER (pattern 3)
--
-- Kafka bảo đảm AT-LEAST-ONCE: một message có thể được giao hai lần. Xảy ra
-- khi consumer xử lý xong nhưng chết trước khi kịp commit offset, hoặc khi
-- group rebalance giữa chừng. Lần giao thứ hai trông y hệt lần đầu.
--
-- Bảng này là trí nhớ của consumer: "event này tôi xử lý rồi".
--
-- THEN CHỐT: dòng này được INSERT trong CÙNG transaction với việc giữ hàng.
-- Hoặc cả hai cùng commit, hoặc cả hai cùng rollback. Nếu tách ra hai
-- transaction thì lại có khe hở: giữ hàng xong, chết trước khi ghi sổ →
-- lần giao lại giữ hàng thêm lần nữa.
--
-- Vì sao PRIMARY KEY + "INSERT ... ON CONFLICT DO NOTHING" thay vì
-- "SELECT xem có chưa, chưa thì INSERT": hai consumer (lúc rebalance) cùng
-- SELECT, cùng thấy chưa có, cùng INSERT → cùng xử lý. ON CONFLICT là một
-- thao tác nguyên tử duy nhất; database quyết định ai thắng, không có khe hở.
-- =============================================================================

CREATE TABLE processed_events (
    event_id        UUID            PRIMARY KEY,

    -- Thao tác nào đã xử lý event này. Hữu ích khi gỡ lỗi, và cho phép
    -- sau này hai handler khác nhau cùng nghe một event mà không giẫm chân.
    operation       VARCHAR(64)     NOT NULL,

    processed_at    TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- Bảng này chỉ tăng. Production cần job xoá bản ghi cũ hơn retention của
-- topic (mặc định Kafka giữ message 7 ngày — quá hạn đó không thể bị giao lại
-- nữa, nên cũng không cần nhớ). Index này phục vụ job đó.
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
