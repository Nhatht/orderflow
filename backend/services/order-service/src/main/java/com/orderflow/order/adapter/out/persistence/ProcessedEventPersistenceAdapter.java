package com.orderflow.order.adapter.out.persistence;

import com.orderflow.order.application.port.out.ProcessedEventPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Cài đặt {@link ProcessedEventPort} bằng SQL thuần.
 *
 * <p><b>Vì sao JdbcTemplate chứ không phải JPA entity + repository:</b>
 * thứ cần ở đây là MỘT câu lệnh nguyên tử có {@code ON CONFLICT DO NOTHING},
 * rồi đọc số dòng bị ảnh hưởng. JPA không có khái niệm đó —
 * {@code repository.save()} với id đã tồn tại sẽ thành UPDATE (merge) hoặc ném
 * exception và làm hỏng cả transaction đang chạy. Chọn công cụ theo việc.
 *
 * <p>JdbcTemplate vẫn chạy CHUNG transaction với JPA: {@code JpaTransactionManager}
 * đưa cùng một JDBC connection cho mọi truy cập trên cùng DataSource.
 */
@Component
@RequiredArgsConstructor
public class ProcessedEventPersistenceAdapter implements ProcessedEventPort {

    private final JdbcTemplate jdbc;

    @Override
    public boolean markProcessed(UUID eventId, String operation) {
        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id, operation)
                VALUES (?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, operation);
        return inserted == 1;
    }
}
