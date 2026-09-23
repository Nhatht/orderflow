package com.orderflow.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.stream.Stream;

/**
 * Cấu hình Kafka của order-service.
 */
@Configuration
public class KafkaConfig {

    /**
     * Topic mà service này SỞ HỮU — service phát event thì khai báo topic.
     *
     * <p>Spring Boot dựng sẵn {@code KafkaAdmin}; lúc khởi động nó thấy bean
     * {@link NewTopic} và tạo topic nếu chưa có (đã có thì bỏ qua). Broker tắt
     * auto-create, nên đây là cách DUY NHẤT topic ra đời — tường minh, có
     * trong code, review được.
     *
     * <p><b>3 partition:</b> số partition là trần của số consumer chạy song
     * song trong một group. 3 cho phép chạy 3 instance inventory cùng lúc.
     * Tăng partition về sau được, nhưng GIẢM thì không — và tăng sẽ làm đổi
     * ánh xạ key→partition, phá thứ tự của các đơn đang chạy dở.
     *
     * <p><b>1 replica:</b> chỉ vì môi trường dev có 1 broker. Production tối
     * thiểu 3 replica + {@code min.insync.replicas=2}, đi cùng
     * {@code acks=all} ở producer thì mới thật sự không mất message khi một
     * broker chết.
     */
    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(Topics.ORDER_CREATED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /** Ba lệnh saga mà order-service phát ra — cùng lý do 3 partition / 1 replica. */
    @Bean
    public NewTopic paymentRequestedTopic() {
        return TopicBuilder.name(Topics.PAYMENT_REQUESTED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic orderConfirmedTopic() {
        return TopicBuilder.name(Topics.ORDER_CONFIRMED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic orderCancelledTopic() {
        return TopicBuilder.name(Topics.ORDER_CANCELLED).partitions(3).replicas(1).build();
    }

    /**
     * Converter cho phía CONSUMER: chuyển chuỗi JSON thành đúng kiểu mà
     * tham số của {@code @KafkaListener} khai báo — kể cả kiểu generic như
     * {@code EventEnvelope<StockReservedEvent>}.
     *
     * <p>Spring Boot tự gắn bean này vào listener container factory. Dùng cho
     * năm listener phản hồi saga trong {@code SagaReplyListener}.
     * Xem giải thích đầy đủ trong {@code KafkaConfig} của inventory-service.
     */
    @Bean
    public RecordMessageConverter kafkaMessageConverter(ObjectMapper objectMapper) {
        return new StringJsonMessageConverter(objectMapper);
    }

    // =========================================================================
    // XỬ LÝ LỖI CONSUMER — Retry có giãn cách + Dead Letter Topic (pattern 5)
    // =========================================================================

    /**
     * Listener ném exception thì làm gì — câu hỏi quan trọng ngang Outbox.
     *
     * <p><b>Mặc định của Spring Kafka là một cái bẫy:</b> thử lại 9 lần, KHÔNG
     * NGHỈ giữa các lần ({@code FixedBackOff(0, 9)}), rồi log và BỎ message,
     * commit offset. Cả 10 lần xong trong vài mili giây. Database chập chờn 2
     * giây ngay sau khi cổng thanh toán đã trừ tiền → cả 10 lần đều trượt →
     * message bị vứt → khách mất tiền, đơn kẹt mãi, không ai biết.
     *
     * <p>(Phát hiện khi review tuần 6: test "phản hồi muộn không làm kẹt
     * consumer" vẫn xanh kể cả khi cố tình cho code ném exception — vì message
     * lỗi biến mất nhanh tới mức không kịp chặn ai.)
     *
     * <p><b>Cấu hình ở đây:</b>
     * <ul>
     *   <li><b>Thử lại có giãn cách tăng dần</b> 1s → 2s → 4s → 8s → 16s → 30s
     *       (~1 phút). Đủ để vượt qua sự cố thoáng qua (database restart, khoá
     *       Redis đang bị giữ, Kafka rebalance). Tổng thời gian phải nhỏ hơn
     *       {@code max.poll.interval.ms} (5 phút) — không thì broker tưởng
     *       consumer chết và chia lại partition.</li>
     *   <li><b>Hết lượt thì chuyển sang {@code <topic>.DLT}</b> thay vì vứt đi.
     *       Message nằm đó kèm header ghi exception, chờ người xem và giao lại.
     *       Mất dữ liệu biến thành việc tồn đọng — thấy được, sửa được.</li>
     *   <li><b>Lỗi không bao giờ tự khỏi thì bỏ qua bước thử lại</b>, đi thẳng
     *       vào DLT: JSON hỏng (Spring Kafka tự xếp loại này) và
     *       {@link IllegalArgumentException} — dữ liệu sai thì thử lại 1 phút
     *       vẫn sai, chỉ chặn partition vô ích.</li>
     * </ul>
     *
     * <p><b>Cái giá:</b> trong lúc thử lại, message đó CHẶN mọi message đứng sau
     * nó trong cùng partition (tối đa ~1 phút). Đó là lý do phản hồi sai lúc
     * phải được bỏ qua chứ không ném lỗi — xem {@code OrderSagaOrchestrator}.
     *
     * <p>Spring Boot tự gắn bean {@link CommonErrorHandler} này vào mọi listener.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // Mặc định ghi sang "<topic gốc>.DLT", CÙNG số partition → topic DLT
        // phải có ít nhất bằng số partition của topic gốc (xem bean bên dưới).
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        var backOff = new ExponentialBackOffWithMaxRetries(6);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000);

        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    /**
     * DLT của sáu topic mà order-service NGHE. Bên nghe sở hữu DLT của mình:
     * message hỏng là chuyện của consumer, không phải của producer.
     * Broker tắt auto-create, nên thiếu bean này thì recoverer ghi vào DLT thất bại.
     */
    @Bean
    public KafkaAdmin.NewTopics deadLetterTopics() {
        return new KafkaAdmin.NewTopics(Stream.of(
                        Topics.STOCK_RESERVED, Topics.STOCK_RESERVATION_FAILED, Topics.STOCK_RELEASED,
                        Topics.STOCK_RESERVATION_EXPIRED, Topics.PAYMENT_COMPLETED, Topics.PAYMENT_FAILED)
                .map(t -> TopicBuilder.name(t + ".DLT").partitions(3).replicas(1).build())
                .toArray(NewTopic[]::new));
    }
}
