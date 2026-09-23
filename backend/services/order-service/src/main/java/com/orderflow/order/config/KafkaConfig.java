package com.orderflow.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

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

    /**
     * Converter cho phía CONSUMER: chuyển chuỗi JSON thành đúng kiểu mà
     * tham số của {@code @KafkaListener} khai báo — kể cả kiểu generic như
     * {@code EventEnvelope<StockReservedEvent>}.
     *
     * <p>Spring Boot tự gắn bean này vào listener container factory. Order
     * chưa có listener nào ở tuần 4; saga orchestrator ở tuần 6 sẽ dùng.
     * Xem giải thích đầy đủ trong {@code KafkaConfig} của inventory-service.
     */
    @Bean
    public RecordMessageConverter kafkaMessageConverter(ObjectMapper objectMapper) {
        return new StringJsonMessageConverter(objectMapper);
    }
}
