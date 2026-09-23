package com.orderflow.order.adapter.out.persistence;

import com.orderflow.order.application.port.out.SagaStateRepositoryPort;
import com.orderflow.order.domain.model.OrderSaga;
import com.orderflow.order.domain.model.SagaStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lưu saga bằng SQL thuần — optimistic locking VIẾT TAY.
 *
 * <p>Order dùng {@code @Version} của JPA; ở đây làm cùng việc đó bằng tay để
 * thấy rõ nó hoạt động thế nào, vì phỏng vấn hay hỏi "{@code @Version} thật
 * ra làm gì":
 * <pre>
 *   UPDATE saga_state SET ..., version = version + 1
 *   WHERE order_id = ? AND version = ?        ← version lúc ĐỌC lên
 * </pre>
 * Có ai sửa xen giữa thì version dưới database đã tăng, điều kiện không khớp,
 * UPDATE ảnh hưởng 0 dòng → ném {@link OptimisticLockingFailureException} →
 * transaction rollback → Kafka giao lại phản hồi → lần sau đọc bản mới nhất.
 * Không khoá gì trong lúc xử lý; chỉ phát hiện xung đột lúc ghi.
 */
@Component
@RequiredArgsConstructor
public class SagaStatePersistenceAdapter implements SagaStateRepositoryPort {

    private final JdbcTemplate jdbc;

    @Override
    public void save(OrderSaga saga) {
        if (saga.version() == null) {
            jdbc.update("""
                    INSERT INTO saga_state (order_id, status, current_step, failure_reason, version, started_at, updated_at)
                    VALUES (?, ?, ?, ?, 0, ?, ?)
                    """,
                    saga.orderId(), saga.status().name(), saga.currentStep().name(), saga.failureReason(),
                    Timestamp.from(saga.startedAt()), Timestamp.from(saga.updatedAt()));
        } else {
            int updated = jdbc.update("""
                    UPDATE saga_state
                    SET status = ?, current_step = ?, failure_reason = ?, updated_at = ?, version = version + 1
                    WHERE order_id = ? AND version = ?
                    """,
                    saga.status().name(), saga.currentStep().name(), saga.failureReason(),
                    Timestamp.from(saga.updatedAt()), saga.orderId(), saga.version());
            if (updated == 0) {
                throw new OptimisticLockingFailureException(
                        "Saga %s was modified concurrently (expected version %d)"
                                .formatted(saga.orderId(), saga.version()));
            }
        }

        // Nhật ký chỉ GHI THÊM, không bao giờ sửa dòng cũ.
        jdbc.batchUpdate("INSERT INTO saga_step_log (order_id, step, outcome, detail) VALUES (?, ?, ?, ?)",
                saga.pendingLog().stream()
                        .map(e -> new Object[]{saga.orderId(), e.step().name(), e.outcome().name(), e.detail()})
                        .toList());
    }

    @Override
    public Optional<OrderSaga> findByOrderId(UUID orderId) {
        return jdbc.query("""
                        SELECT order_id, status, current_step, failure_reason, started_at, updated_at, version
                        FROM saga_state WHERE order_id = ?
                        """,
                (rs, i) -> new OrderSaga(
                        rs.getObject("order_id", UUID.class),
                        SagaStatus.valueOf(rs.getString("status")),
                        OrderSaga.Step.valueOf(rs.getString("current_step")),
                        rs.getString("failure_reason"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getLong("version")),
                orderId).stream().findFirst();
    }

    @Override
    public List<StepRecord> history(UUID orderId) {
        return jdbc.query("""
                        SELECT step, outcome, detail, occurred_at
                        FROM saga_step_log WHERE order_id = ? ORDER BY id
                        """,
                (rs, i) -> new StepRecord(rs.getString("step"), rs.getString("outcome"),
                        rs.getString("detail"), rs.getTimestamp("occurred_at").toInstant()),
                orderId);
    }
}
