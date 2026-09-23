package com.orderflow.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

import java.util.stream.Stream;

/**
 * Kafka của notification-service. Converter: xem {@code KafkaConfig} bên inventory.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public RecordMessageConverter kafkaMessageConverter(ObjectMapper objectMapper) {
        return new StringJsonMessageConverter(objectMapper);
    }

    /**
     * Máy chủ SMTP sập là lỗi THOÁNG QUA điển hình: thử lại có giãn cách
     * (1s → 2s → … tối đa 30s, 6 lần), hết lượt thì chuyển message sang
     * {@code <topic>.DLT} thay vì vứt — thư chưa gửi thành việc tồn đọng thấy
     * được. Mặc định của Spring Kafka (10 lần không nghỉ rồi bỏ) sẽ làm mất thư
     * ngay trong một lần mail server restart.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var backOff = new ExponentialBackOffWithMaxRetries(6);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000);
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".notification.DLT", record.partition()));
        return new DefaultErrorHandler(recoverer, backOff);
    }

    /**
     * DLT RIÊNG của notification: {@code order.*.notification.DLT}. Inventory
     * cũng nghe hai topic này; nếu cả hai cùng ghi vào {@code order.confirmed.DLT}
     * thì không biết message hỏng là của ai. Tên DLT theo group nghe, không theo topic.
     */
    @Bean
    public KafkaAdmin.NewTopics deadLetterTopics() {
        return new KafkaAdmin.NewTopics(Stream.of(Topics.ORDER_CONFIRMED, Topics.ORDER_CANCELLED)
                .map(t -> TopicBuilder.name(t + ".notification.DLT").partitions(3).replicas(1).build())
                .toArray(NewTopic[]::new));
    }
}
