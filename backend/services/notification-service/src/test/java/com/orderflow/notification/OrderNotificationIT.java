package com.orderflow.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.order.OrderCancelledEvent;
import com.orderflow.contracts.order.OrderConfirmedEvent;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TUẦN 7 — notification-service trên PostgreSQL + Kafka + MailHog THẬT.
 *
 * <p><b>Vì sao MailHog chứ không GreenMail:</b> MailHog chính là thứ đang chạy
 * trong docker-compose dev — test và môi trường dev dùng cùng một máy chủ
 * SMTP. Nó có sẵn HTTP API ({@code /api/v2/search}) để hỏi "đã nhận bao nhiêu
 * thư gửi tới địa chỉ X", đúng thứ test cần. GreenMail chạy nhúng trong JVM thì
 * nhanh hơn, nhưng là một máy chủ khác với cái ta dùng thật.
 */
@SpringBootTest
class OrderNotificationIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notification_db").withUsername("orderflow").withPassword("orderflow");

    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    static final GenericContainer<?> MAILHOG = new GenericContainer<>("mailhog/mailhog:v1.0.1")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v2/messages").forPort(8025));

    static {
        POSTGRES.start();
        KAFKA.start();
        MAILHOG.start();
        // Hai topic này thuộc order-service; ở đây không có order-service nên test tạo thay.
        try (var admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(Topics.ORDER_CONFIRMED, 3, (short) 1),
                    new NewTopic(Topics.ORDER_CANCELLED, 3, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.mail.host", MAILHOG::getHost);
        registry.add("spring.mail.port", () -> MAILHOG.getMappedPort(1025));
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    // =========================================================================

    @Test
    @DisplayName("order.confirmed → đúng một email xác nhận tới khách")
    void confirmedOrderSendsEmail() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customer = UUID.randomUUID();

        send(Topics.ORDER_CONFIRMED, orderId, EventEnvelope.of(OrderConfirmedEvent.TYPE, orderId.toString(), "c",
                new OrderConfirmedEvent(orderId, customer)));

        assertThat(awaitMailCount(customer, 1)).isEqualTo(1);
        assertThat(ledgerStatus(orderId)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("order.cancelled → email báo huỷ")
    void cancelledOrderSendsEmail() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customer = UUID.randomUUID();

        send(Topics.ORDER_CANCELLED, orderId, EventEnvelope.of(OrderCancelledEvent.TYPE, orderId.toString(), "c",
                new OrderCancelledEvent(orderId, customer, OrderCancelledEvent.Reason.PAYMENT_DECLINED)));

        assertThat(awaitMailCount(customer, 1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT kind FROM notifications WHERE order_id = ?", String.class, orderId))
                .isEqualTo("ORDER_CANCELLED");
    }

    @Test
    @DisplayName("Cùng một event đến hai lần → vẫn chỉ MỘT email")
    void duplicateDeliverySendsOneEmail() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        var envelope = EventEnvelope.of(OrderConfirmedEvent.TYPE, orderId.toString(), "c",
                new OrderConfirmedEvent(orderId, customer));

        send(Topics.ORDER_CONFIRMED, orderId, envelope);
        awaitMailCount(customer, 1);
        send(Topics.ORDER_CONFIRMED, orderId, envelope);   // at-least-once

        // Lính canh cùng key → cùng partition → xử lý SAU bản trùng.
        UUID sentinelOrder = UUID.randomUUID();
        UUID sentinelCustomer = UUID.randomUUID();
        send(Topics.ORDER_CONFIRMED, orderId, EventEnvelope.of(OrderConfirmedEvent.TYPE, sentinelOrder.toString(), "c",
                new OrderConfirmedEvent(sentinelOrder, sentinelCustomer)));
        awaitMailCount(sentinelCustomer, 1);

        assertThat(mailCount(customer)).as("bản trùng không gửi thêm thư").isEqualTo(1);
    }

    @Test
    @DisplayName("Lần trước chết khi mới ghi PENDING (chưa gửi) → lần giao lại vẫn gửi, đúng một thư")
    void pendingFromCrashedAttemptIsStillSent() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        var envelope = EventEnvelope.of(OrderConfirmedEvent.TYPE, orderId.toString(), "c",
                new OrderConfirmedEvent(orderId, customer));
        // Dựng lại trạng thái sau khi app chết giữa bước 1 và bước 3.
        jdbc.update("INSERT INTO notifications (event_id, order_id, kind, recipient, status) VALUES (?, ?, ?, ?, 'PENDING')",
                envelope.eventId(), orderId, "ORDER_CONFIRMED", "customer-%s@orderflow.local".formatted(customer));

        send(Topics.ORDER_CONFIRMED, orderId, envelope);

        assertThat(awaitMailCount(customer, 1))
                .as("PENDING nghĩa là 'chưa chắc đã gửi' — phải gửi, không được coi là xong").isEqualTo(1);
        assertThat(ledgerStatus(orderId)).isEqualTo("SENT");
    }

    // ---- helper ------------------------------------------------------------

    private void send(String topic, UUID key, EventEnvelope<?> envelope) throws Exception {
        kafkaTemplate.send(topic, key.toString(), objectMapper.writeValueAsString(envelope)).get();
    }

    private String ledgerStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE order_id = ?", String.class, orderId);
    }

    /** Hỏi MailHog: đã nhận bao nhiêu thư gửi tới khách này. */
    private int mailCount(UUID customer) throws Exception {
        String to = URLEncoder.encode("customer-%s@orderflow.local".formatted(customer), StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create("http://%s:%d/api/v2/search?kind=to&query=%s"
                .formatted(MAILHOG.getHost(), MAILHOG.getMappedPort(8025), to))).GET().build();
        JsonNode body = objectMapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
        return body.get("total").asInt();
    }

    private int awaitMailCount(UUID customer, int expected) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        int count = mailCount(customer);
        while (count < expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(300);
            count = mailCount(customer);
        }
        return count;
    }
}
