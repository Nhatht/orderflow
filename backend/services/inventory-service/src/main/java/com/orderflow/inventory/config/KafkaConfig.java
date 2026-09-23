package com.orderflow.inventory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

/**
 * Cấu hình Kafka của inventory-service.
 */
@Configuration
public class KafkaConfig {

    /**
     * Hai topic inventory SỞ HỮU (nó là bên phát). Topic {@code order.created}
     * thuộc order-service, inventory chỉ đọc nên không khai báo ở đây.
     * Lý do chọn 3 partition / 1 replica: xem {@code KafkaConfig} của order-service.
     */
    @Bean
    public NewTopic stockReservedTopic() {
        return TopicBuilder.name(Topics.STOCK_RESERVED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic stockReservationFailedTopic() {
        return TopicBuilder.name(Topics.STOCK_RESERVATION_FAILED).partitions(3).replicas(1).build();
    }

    /**
     * Chuyển chuỗi JSON thành kiểu mà tham số {@code @KafkaListener} khai báo.
     *
     * <p><b>Vì sao không dùng {@code JsonDeserializer} như cấu hình mặc định hay thấy:</b>
     * {@code JsonDeserializer} chạy ở tầng Kafka client, TRƯỚC khi Spring biết
     * message sẽ đi vào method nào. Nó chỉ biết kiểu đích qua header
     * {@code __TypeId__} hoặc một class cấu hình cứng — và cả hai đều là kiểu
     * THÔ: {@code EventEnvelope}, không có {@code <OrderCreatedEvent>}. Kết quả
     * là {@code payload} thành {@code LinkedHashMap}, và mọi listener phải tự
     * ép kiểu bằng tay.
     *
     * <p>Converter này chạy SAU, ở tầng Spring, khi đã biết method đích. Nó đọc
     * kiểu generic từ chữ ký {@code onOrderCreated(EventEnvelope<OrderCreatedEvent>)}
     * và đưa cho Jackson. Payload ra đúng kiểu, không cần header nào.
     *
     * <p>Lợi ích phụ: message JSON hỏng không làm sập consumer ở tầng
     * deserializer (vòng lặp "poison pill" kinh điển). Deserializer chỉ đọc
     * chuỗi — không bao giờ lỗi — còn lỗi parse JSON nổ ra trong converter và
     * đi qua error handler như mọi lỗi khác. Spring Kafka xếp lỗi convert vào
     * loại KHÔNG thử lại: thử lại một JSON hỏng thì nó vẫn hỏng.
     *
     * <p>Dùng chung {@link ObjectMapper} của Spring Boot để mọi nơi trong service
     * serialize ngày giờ, tiền, null theo cùng một quy tắc.
     */
    @Bean
    public RecordMessageConverter kafkaMessageConverter(ObjectMapper objectMapper) {
        return new StringJsonMessageConverter(objectMapper);
    }
}
