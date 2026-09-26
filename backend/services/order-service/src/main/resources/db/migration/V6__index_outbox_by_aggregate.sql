-- =============================================================================
-- V6: Index outbox theo (aggregate_id, topic)
--
-- Saga timeout giờ hỏi "lệnh payment.requested của đơn này đã lên Kafka lúc
-- nào?" (SagaStatePersistenceAdapter.findAwaitingPaymentRequestedBefore). Bảng
-- outbox giữ dòng đã gửi 7 ngày; không có index thì mỗi lần quét 30 giây là một
-- lần duyệt cả bảng cho từng saga đang chờ tiền.
-- =============================================================================

CREATE INDEX idx_outbox_aggregate_topic ON outbox (aggregate_id, topic);
