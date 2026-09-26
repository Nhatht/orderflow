package com.orderflow.inventory.adapter.out.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Nửa sau của Transactional Outbox: đọc dòng chưa gửi, đẩy lên Kafka,
 * đánh dấu đã gửi.
 *
 * <p><b>Class này là BẢN SAO của {@code OutboxPoller} bên order-service — cố ý.</b>
 * Đưa nó vào {@code shared/} thì mọi service phụ thuộc vào cùng một thư viện
 * hạ tầng: sửa poller là phải build và deploy lại tất cả — đúng định nghĩa
 * distributed monolith. {@code shared/} chỉ chứa contract (xem
 * {@code docs/STRUCTURE.md}). Ở công ty thật, chỗ này thường là một thư viện
 * nội bộ có version riêng, hoặc Debezium — không cần code poller nào cả.
 *
 * <h2>Một vòng relay</h2>
 * <pre>
 *   BEGIN
 *     SELECT ... WHERE published_at IS NULL ... FOR UPDATE SKIP LOCKED
 *     gửi cả lô lên Kafka, CHỜ broker xác nhận
 *     UPDATE published_at = now()
 *   COMMIT
 * </pre>
 * Kafka lỗi giữa chừng → exception → ROLLBACK → các dòng vẫn chưa gửi →
 * vòng sau thử lại. Không mất gì.
 *
 * <h2>At-least-once, không phải exactly-once</h2>
 * Kafka đã nhận, nhưng xác nhận không về kịp (hoặc app chết ngay sau khi gửi,
 * trước COMMIT) → dòng vẫn là "chưa gửi" → vòng sau gửi LẠI. Trùng là chuyện
 * chắc chắn xảy ra, không phải có thể. Chấp nhận được vì bản gửi lại mang
 * cùng {@code eventId} (= id của dòng outbox), và consumer idempotent.
 *
 * <h2>Chạy nhiều instance — vì sao không cần ShedLock</h2>
 * Hai mệnh đề, hai nhiệm vụ khác nhau (đã đo bằng {@code OutboxIT}, 4 poller
 * tranh 40 event):
 * <ul>
 *   <li><b>Bỏ cả hai</b> → mỗi event lên Kafka <b>4 lần</b>: bốn poller cùng
 *       đọc thấy cùng một lô "chưa gửi".</li>
 *   <li><b>Chỉ {@code FOR UPDATE}</b> → đúng 1 lần. Poller sau phải CHỜ khoá;
 *       khi poller trước commit, PostgreSQL kiểm tra lại
 *       {@code published_at IS NULL} trên phiên bản mới của dòng và loại nó.
 *       Đúng, nhưng các poller chạy lần lượt — thêm instance không nhanh hơn.</li>
 *   <li><b>{@code FOR UPDATE SKIP LOCKED}</b> → đúng 1 lần VÀ song song: poller
 *       sau bỏ qua dòng đang bị khoá, lấy ngay lô kế tiếp.</li>
 * </ul>
 * Tóm lại: {@code FOR UPDATE} lo tính ĐÚNG, {@code SKIP LOCKED} lo THÔNG LƯỢNG.
 * Database tự chia việc — không cần khoá phân tán.
 *
 * <h2>Vì sao phải CHỜ Kafka xác nhận (không fire-and-forget như tuần 4)</h2>
 * Đánh dấu {@code published_at} khi chưa chắc Kafka đã nhận thì lại đúng lỗi
 * cũ: dòng bị coi là đã gửi, message thì chưa tới đâu.
 */
@Slf4j
@Component
public class OutboxPoller {

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final OutboxTracing tracing;

    public OutboxPoller(JdbcTemplate jdbc,
                        KafkaTemplate<String, String> kafkaTemplate,
                        TransactionTemplate tx,
                        @Value("${orderflow.outbox.batch-size:100}") int batchSize,
                        @Value("${orderflow.outbox.send-timeout:PT15S}") Duration sendTimeout,
                        @Value("${orderflow.outbox.retention:P7D}") Duration retention,
                        OutboxTracing tracing) {
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.tx = tx;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.tracing = tracing;
    }

    private record OutboxRow(UUID id, String topic, String key, String payload, String traceParent) {}

    /**
     * {@code fixedDelay}, không phải {@code fixedRate}: vòng sau bắt đầu 500ms
     * SAU KHI vòng trước kết thúc. Kafka chậm thì các vòng giãn ra, không
     * chồng lên nhau.
     *
     * <p>Độ trễ tối đa ~500ms từ lúc commit tới lúc lên Kafka là cái giá của
     * polling. Muốn gần như tức thì thì dùng CDC (Debezium đọc WAL của
     * PostgreSQL) — không phải poll, không tải thêm database, nhưng phải vận
     * hành thêm Kafka Connect. Với dự án này, 500ms là đủ.
     */
    @Scheduled(fixedDelayString = "${orderflow.outbox.poll-interval:PT0.5S}")
    public void poll() {
        try {
            int relayed;
            do {
                relayed = relayBatch();
            } while (relayed == batchSize);   // còn tồn đọng thì xả tiếp, không chờ nhịp sau
        } catch (Exception e) {
            // Không để exception thoát ra: scheduler vẫn chạy nhịp sau, nhưng
            // log sẽ ngập stack trace mỗi 500ms suốt lúc Kafka sập.
            log.warn("Outbox relay failed, rows stay pending and will be retried: {}", e.toString());
        }
    }

    /** Một lô. Public để test gọi trực tiếp từ nhiều luồng. */
    public int relayBatch() {
        Integer relayed = tx.execute(status -> {
            List<OutboxRow> rows = jdbc.query("""
                    SELECT id, topic, aggregate_id, payload::text AS payload, trace_parent
                    FROM outbox
                    WHERE published_at IS NULL
                    ORDER BY seq
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """,
                    (rs, i) -> new OutboxRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("topic"),
                            rs.getString("aggregate_id"),
                            rs.getString("payload"),
                            rs.getString("trace_parent")),
                    batchSize);

            if (rows.isEmpty()) {
                return 0;
            }

            // Gửi cả lô rồi chờ một lần — nhanh hơn nhiều so với gửi-chờ từng
            // cái. Thứ tự trong cùng partition vẫn giữ nhờ idempotent producer.
            List<CompletableFuture<SendResult<String, String>>> sends = rows.stream()
                    // Gửi trong span con của trace GỐC (lưu lúc ghi outbox) — xem OutboxTracing.
                    .map(r -> tracing.inStoredTrace(r.traceParent(), "outbox relay " + r.topic(),
                            () -> kafkaTemplate.send(tracing.record(r.topic(), r.key(), r.payload(), r.traceParent()))))
                    .toList();
            try {
                CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while relaying outbox", e);
            } catch (Exception e) {
                // Ném ra → ROLLBACK → mọi dòng của lô vẫn là "chưa gửi".
                // Những cái đã tới Kafka sẽ bị gửi lại — consumer idempotent lo.
                throw new IllegalStateException("Kafka did not acknowledge outbox batch", e);
            }

            jdbc.batchUpdate("UPDATE outbox SET published_at = now() WHERE id = ?",
                    rows.stream().map(r -> new Object[]{r.id()}).toList());

            log.debug("Relayed {} outbox event(s)", rows.size());
            return rows.size();
        });
        return relayed == null ? 0 : relayed;
    }

    /**
     * Xoá dòng đã gửi lâu hơn thời gian giữ lại. Không có job này, bảng outbox
     * phình mãi. Giữ lại một thời gian (mặc định 7 ngày, bằng retention mặc
     * định của Kafka) để còn tra cứu khi gỡ lỗi "event này đã được gửi chưa".
     *
     * <p>Nhiều instance cùng chạy job này thì cũng chỉ là cùng một câu DELETE —
     * vô hại, nên không cần khoá.
     */
    @Scheduled(cron = "${orderflow.outbox.cleanup-cron:0 0 3 * * *}")
    public void deletePublishedRows() {
        int deleted = jdbc.update(
                "DELETE FROM outbox WHERE published_at < now() - make_interval(secs => ?)",
                retention.toSeconds());
        if (deleted > 0) {
            log.info("Outbox cleanup removed {} published row(s) older than {}", deleted, retention);
        }
    }
}
