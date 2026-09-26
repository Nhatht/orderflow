package com.orderflow.payment.adapter.out.persistence;

import com.orderflow.payment.application.port.out.CancelledOrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** JDBC thuần — một bảng hai cột, không cần entity JPA. */
@Component
@RequiredArgsConstructor
public class CancelledOrderJdbcAdapter implements CancelledOrderPort {

    private final JdbcTemplate jdbc;

    @Override
    public boolean markCancelled(UUID orderId, String reason) {
        return jdbc.update("INSERT INTO cancelled_orders (order_id, reason) VALUES (?, ?) ON CONFLICT DO NOTHING",
                orderId, reason) == 1;
    }

    @Override
    public boolean isCancelled(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM cancelled_orders WHERE order_id = ?)", Boolean.class, orderId));
    }
}
