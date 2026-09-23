-- =============================================================================
-- V4: Nới saga_state.failure_reason từ 64 lên 255 ký tự
--
-- Phát hiện khi review tuần 6: lý do thất bại của thanh toán là chuỗi tự do từ
-- cổng thanh toán (payments.failure_reason cho phép 255). Lý do dài hơn 64 ký
-- tự làm UPDATE saga_state lỗi → phản hồi bị thử lại rồi vào DLT → đơn không
-- bao giờ được huỷ, hàng bị giữ mãi.
--
-- Sửa bằng migration MỚI, KHÔNG sửa V3: V3 đã chạy trên database thật. Flyway
-- lưu checksum của mọi migration đã áp dụng; sửa file cũ thì lần khởi động sau
-- báo "checksum mismatch" và service không lên. Migration đã chạy là bất biến.
-- =============================================================================

ALTER TABLE saga_state ALTER COLUMN failure_reason TYPE VARCHAR(255);
