package com.orderflow.notification.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Sổ ghi thông báo — xem giải thích trong {@code V1__create_notifications.sql}.
 *
 * <p>SQL thuần qua JdbcTemplate: cần {@code ON CONFLICT DO NOTHING} nguyên tử,
 * thứ JPA không có. Mỗi câu tự commit ngay — cố ý không bọc transaction, vì
 * trạng thái PENDING phải nằm xuống database TRƯỚC khi gửi mail.
 */
@Repository
@RequiredArgsConstructor
public class NotificationLedger {

    private final JdbcTemplate jdbc;

    /**
     * Ghi ý định gửi (nếu chưa có) rồi trả về trạng thái hiện tại.
     *
     * @return {@code true} nếu thư cho event này ĐÃ gửi xong trước đó
     */
    public boolean recordIntentAndCheckSent(UUID eventId, UUID orderId, String kind, String recipient) {
        jdbc.update("""
                INSERT INTO notifications (event_id, order_id, kind, recipient, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, orderId, kind, recipient);
        String status = jdbc.queryForObject(
                "SELECT status FROM notifications WHERE event_id = ?", String.class, eventId);
        return "SENT".equals(status);
    }

    public void markSent(UUID eventId) {
        jdbc.update("UPDATE notifications SET status = 'SENT', sent_at = now() WHERE event_id = ?", eventId);
    }
}
