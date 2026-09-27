-- =============================================================================
-- V5 (tuần 10): sản phẩm demo để xem saga ĐỀN BÙ từ giao diện.
--
-- Cổng thanh toán giả lập (payment-service, SimulatedPaymentGateway) từ chối
-- mọi đơn có PHẦN NGUYÊN của tổng tiền tận cùng bằng 99.
--
-- Với ba sản phẩm seed ở V1 thì KHÔNG BAO GIỜ đạt được tổng đó:
--   45 000 và 99 000  → luôn góp "...00"
--   25 500,5 × b      → hai chữ số cuối của phần nguyên là floor(b/2) mod 100,
--                       cần b = 198 hoặc 199 trong khi kho chỉ có 50.
-- Trước tuần 10 không sao vì curl tự gửi unitPrice bất kỳ (order-service tin giá
-- client gửi, HANDOFF D.6). Từ tuần 10, frontend gửi đúng giá catalog, nên cần
-- một sản phẩm mà giá tự nó đã tận cùng 99: một cái → 30 099 → bị từ chối;
-- thêm Kẹo dừa (+45 000) vẫn tận cùng 99.
--
-- Migration MỚI chứ không sửa V1: Flyway lưu checksum từng file đã chạy; sửa V1
-- thì mọi database đã migrate sẽ báo lỗi checksum khi khởi động.
-- =============================================================================
INSERT INTO products (id, sku, name, price, currency) VALUES
    ('44444444-4444-4444-4444-444444444444', 'DECL-99', 'Trà atiso Đà Lạt', 30099.0000, 'VND');

INSERT INTO stock (product_id, available_qty, reserved_qty) VALUES
    ('44444444-4444-4444-4444-444444444444', 200, 0);
