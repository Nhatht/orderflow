package com.orderflow.notification.service;

import com.orderflow.notification.repository.NotificationLedger;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Gửi email cho khách khi đơn có kết cục — tầng service của kiến trúc LAYERED.
 *
 * <p>Không hexagonal như ba service kia (xem {@code docs/STRUCTURE.md}): ở đây
 * không có luật nghiệp vụ nào đáng bảo vệ bằng port/adapter. Nghe event → tra
 * sổ → gửi thư. Ba lớp listener → service → repository là đủ.
 *
 * <p><b>Người nhận là địa chỉ giả lập</b> {@code customer-<id>@orderflow.local}:
 * dự án không có customer-service lưu email. Làm thật thì tra email từ
 * customer-service, hoặc gắn email vào claim JWT rồi đi theo đơn.
 */
@Slf4j
@Service
public class NotificationService {

    public enum Kind { ORDER_CONFIRMED, ORDER_CANCELLED }

    private final NotificationLedger ledger;
    private final JavaMailSenderImpl mailSender;
    private final String from;

    public NotificationService(NotificationLedger ledger,
                               JavaMailSenderImpl mailSender,
                               @Value("${orderflow.notification.from:no-reply@orderflow.local}") String from) {
        this.ledger = ledger;
        this.mailSender = mailSender;
        this.from = from;
    }

    public void notify(UUID eventId, UUID orderId, UUID customerId, Kind kind, String detail) {
        if (customerId == null) {
            // Event phát trước tuần 7 chưa có customerId. Consumer group mới đọc
            // từ đầu topic (earliest) nên sẽ gặp chúng. Không biết gửi cho ai →
            // bỏ qua. KHÔNG ném lỗi: thử lại cũng không bao giờ có customerId,
            // chỉ làm retry vô ích rồi rác trong DLT.
            log.warn("Event {} for order={} has no customerId (published before week 7), skipping", eventId, orderId);
            return;
        }
        String recipient = "customer-%s@orderflow.local".formatted(customerId);

        if (ledger.recordIntentAndCheckSent(eventId, orderId, kind.name(), recipient)) {
            log.info("Notification for eventId={} already sent, skipping duplicate delivery", eventId);
            return;
        }

        send(eventId, recipient, subject(kind, orderId), body(kind, orderId, detail));
        ledger.markSent(eventId);
        log.info("Sent {} email for order={} to {}", kind, orderId, recipient);
    }

    /**
     * Message-ID CỐ ĐỊNH theo eventId: nếu phải gửi lại (chết giữa lúc gửi và
     * lúc ghi SENT), hai bản thư mang cùng Message-ID và nhiều mail client coi
     * là một. SMTP không có idempotency key như cổng thanh toán — đây là thứ
     * gần nhất có được.
     *
     * <p>Phải ghi đè {@code updateMessageID()}: JavaMail tự sinh Message-ID mới
     * mỗi lần {@code saveChanges()}, xoá mất header đặt tay.
     */
    private void send(UUID eventId, String to, String subject, String text) {
        Session session = mailSender.getSession();
        MimeMessage message = new MimeMessage(session) {
            @Override
            protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", "<" + eventId + "@orderflow.local>");
            }
        };
        try {
            message.setFrom(new InternetAddress(from));
            message.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(to));
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            message.setText(text, StandardCharsets.UTF_8.name());
        } catch (MessagingException e) {
            throw new IllegalStateException("Cannot build email for event " + eventId, e);
        }
        // Lỗi SMTP (máy chủ mail sập) bay lên listener → retry có giãn cách → DLT.
        mailSender.send(message);
    }

    private static String subject(Kind kind, UUID orderId) {
        return switch (kind) {
            case ORDER_CONFIRMED -> "Đơn hàng %s đã được xác nhận".formatted(orderId);
            case ORDER_CANCELLED -> "Đơn hàng %s đã bị huỷ".formatted(orderId);
        };
    }

    private static String body(Kind kind, UUID orderId, String detail) {
        return switch (kind) {
            case ORDER_CONFIRMED -> """
                    Cảm ơn bạn! Đơn hàng %s đã thanh toán thành công và đang được chuẩn bị.
                    """.formatted(orderId);
            case ORDER_CANCELLED -> """
                    Rất tiếc, đơn hàng %s đã bị huỷ (lý do: %s).
                    Nếu bạn đã bị trừ tiền, khoản tiền sẽ được hoàn lại.
                    """.formatted(orderId, detail);
        };
    }
}
