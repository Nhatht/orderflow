package com.orderflow.payment.config;

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
 * Cấu hình Kafka của payment-service. Giải thích từng quyết định: xem
 * {@code KafkaConfig} của order-service (topic) và inventory-service (converter).
 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic paymentCompletedTopic() {
        return TopicBuilder.name(Topics.PAYMENT_COMPLETED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailedTopic() {
        return TopicBuilder.name(Topics.PAYMENT_FAILED).partitions(3).replicas(1).build();
    }

    @Bean
    public RecordMessageConverter kafkaMessageConverter(ObjectMapper objectMapper) {
        return new StringJsonMessageConverter(objectMapper);
    }

    /**
     * Retry có giãn cách + Dead Letter Topic — cùng cấu hình và cùng lý do với
     * {@code KafkaConfig} của order-service (đọc javadoc đầy đủ ở đó). Mặc định
     * của Spring Kafka thử lại 10 lần không nghỉ rồi VỨT message.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var backOff = new ExponentialBackOffWithMaxRetries(6);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000);
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(deadLetterTopicOf(record.topic()), record.partition()));
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    /**
     * {@code payment.requested} chỉ payment nghe → {@code payment.requested.DLT}.
     * {@code order.cancelled} thì inventory và notification cũng nghe → DLT RIÊNG
     * {@code order.cancelled.payment.DLT}, không thì không biết message hỏng là của
     * ai (cùng lý do với notification). Tên DLT theo group nghe, không theo topic.
     */
    static String deadLetterTopicOf(String topic) {
        return Topics.ORDER_CANCELLED.equals(topic) ? topic + ".payment.DLT" : topic + ".DLT";
    }

    /** DLT của các topic service này NGHE — bên nghe sở hữu DLT của mình. */
    @Bean
    public KafkaAdmin.NewTopics deadLetterTopics() {
        return new KafkaAdmin.NewTopics(Stream.of(Topics.PAYMENT_REQUESTED, Topics.ORDER_CANCELLED)
                .map(t -> TopicBuilder.name(deadLetterTopicOf(t)).partitions(3).replicas(1).build())
                .toArray(NewTopic[]::new));
    }
}
