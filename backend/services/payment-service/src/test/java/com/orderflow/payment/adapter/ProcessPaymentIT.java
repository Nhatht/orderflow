package com.orderflow.payment.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.Topics;
import com.orderflow.contracts.payment.PaymentCompletedEvent;
import com.orderflow.contracts.payment.PaymentFailedEvent;
import com.orderflow.contracts.payment.PaymentRequestedEvent;
import com.orderflow.payment.adapter.out.gateway.SimulatedPaymentGateway;
import com.orderflow.payment.application.dto.ProcessPaymentCommand;
import com.orderflow.contracts.order.OrderCancelledEvent;
import com.orderflow.payment.application.port.in.CancelOrderPaymentUseCase;
import com.orderflow.payment.application.port.in.ProcessPaymentUseCase;
import com.orderflow.payment.application.port.out.PaymentRepositoryPort;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.PaymentStatus;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * TIÊU CHÍ NGHIỆM THU TUẦN 6 — phía payment.
 *
 * <p>Ba test đầu đi qua Kafka thật, như order-service sẽ gọi. Hai test sau gọi
 * thẳng use case để điều khiển chính xác thời điểm "chết giữa chừng" và
 * "đồng thời" — những thứ không tái hiện được một cách chắc chắn qua Kafka.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class ProcessPaymentIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db").withUsername("orderflow").withPassword("orderflow");

    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    static {
        POSTGRES.start();
        KAFKA.start();
        // payment.requested thuộc order-service; ở đây không có order-service nên test tạo thay.
        try (var admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(Topics.PAYMENT_REQUESTED, 3, (short) 1),
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
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProcessPaymentUseCase processPayment;
    @Autowired CancelOrderPaymentUseCase cancelOrderPayment;
    @Autowired PaymentRepositoryPort payments;
    @Autowired SimulatedPaymentGateway gateway;
    @Autowired JdbcTemplate jdbc;

    private KafkaConsumer<String, String> resultConsumer;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        resultConsumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        resultConsumer.subscribe(List.of(Topics.PAYMENT_COMPLETED, Topics.PAYMENT_FAILED));
    }

    @AfterEach
    void close() {
        resultConsumer.close();
    }

    // =========================================================================
    // Qua Kafka
    // =========================================================================

    @Test
    @DisplayName("payment.requested → cổng chấp nhận → payment.completed")
    void approvedPaymentPublishesCompleted() throws Exception {
        UUID orderId = UUID.randomUUID();
        var request = request(orderId, "45000");

        send(request);

        var record = awaitRecord(Topics.PAYMENT_COMPLETED, orderId.toString());
        EventEnvelope<PaymentCompletedEvent> completed = objectMapper.readValue(record.value(), new TypeReference<>() {});
        assertThat(completed.payload().amount()).isEqualByComparingTo("45000");
        assertThat(completed.correlationId()).isEqualTo(request.correlationId());

        Payment payment = payments.findByOrderId(orderId).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.gatewayReference()).startsWith("SIM-");
        assertThat(completed.payload().paymentId()).isEqualTo(payment.id());
    }

    @Test
    @DisplayName("Số tiền tận cùng 99 → cổng từ chối → payment.failed")
    void declinedPaymentPublishesFailed() throws Exception {
        UUID orderId = UUID.randomUUID();

        send(request(orderId, "45099"));

        var record = awaitRecord(Topics.PAYMENT_FAILED, orderId.toString());
        EventEnvelope<PaymentFailedEvent> failed = objectMapper.readValue(record.value(), new TypeReference<>() {});
        assertThat(failed.payload().reason()).isEqualTo("CARD_DECLINED");
        assertThat(payments.findByOrderId(orderId).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("Lệnh thu tiền đến hai lần với eventId KHÁC nhau → vẫn chỉ thu một lần")
    void repeatedRequestWithNewEventIdChargesOnce() throws Exception {
        UUID orderId = UUID.randomUUID();

        send(request(orderId, "45000"));
        awaitRecord(Topics.PAYMENT_COMPLETED, orderId.toString());
        // Không phải bản trùng của Kafka — một lệnh MỚI, eventId mới, cho cùng đơn.
        // processed_events theo eventId sẽ để lọt; UNIQUE(order_id) thì không.
        send(request(orderId, "45000"));
        send(request(orderId, "45000"));

        // Lính canh: cùng key → cùng partition → đi sau hai lệnh lặp.
        UUID sentinel = UUID.randomUUID();
        send(request(sentinel, "1000"), orderId.toString());
        awaitRecord(Topics.PAYMENT_COMPLETED, sentinel.toString());

        Payment payment = payments.findByOrderId(orderId).orElseThrow();
        assertThat(gateway.timesProcessed(payment.id())).isEqualTo(1);
        assertThat(countSeen(Topics.PAYMENT_COMPLETED, orderId.toString())).isEqualTo(1);
    }

    // =========================================================================
    // Gọi thẳng use case — điều khiển chính xác thời điểm hỏng
    // =========================================================================

    @Test
    @DisplayName("Cổng ĐÃ trừ tiền rồi app chết trước khi ghi kết quả → xử lý lại KHÔNG trừ lần hai")
    void crashAfterGatewayChargeDoesNotChargeTwice() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        // Dựng lại đúng trạng thái sau khi app chết:
        // (1) TX1 đã commit — payment PENDING nằm trong database
        jdbc.update("""
                INSERT INTO payments (id, order_id, customer_id, amount, currency, status)
                VALUES (?, ?, ?, ?, 'VND', 'PENDING')
                """, paymentId, orderId, customerId, new BigDecimal("45000"));
        // (2) cổng đã trừ tiền với key = paymentId
        gateway.charge(paymentId, new BigDecimal("45000"), "VND");
        int chargesBeforeRetry = gateway.totalProcessed();
        // (3) ...rồi app chết trước TX2. Kafka giao lại lệnh:

        Payment result = processPayment.process(new ProcessPaymentCommand(
                "corr", orderId, customerId, new BigDecimal("45000"), "VND"));

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.id()).as("dùng lại payment cũ, không tạo cái mới").isEqualTo(paymentId);
        // Đếm TỔNG, không đếm theo key: nếu code sinh key mới cho lần gọi lại,
        // lần trừ tiền thứ hai nằm dưới key khác và phép đếm theo paymentId
        // vẫn ra 1. Đã thử cài đúng lỗi đó — đếm theo key vẫn xanh, đếm tổng thì đỏ.
        assertThat(gateway.totalProcessed() - chargesBeforeRetry)
                .as("lần xử lý lại KHÔNG tạo giao dịch mới nào ở cổng — khách không bị trừ tiền lần hai")
                .isZero();
        assertThat(outboxRows(orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("5 lần giao cùng lúc cho một đơn → một payment, cổng xử lý một lần, một event")
    void concurrentDeliveriesChargeOnce() throws Exception {
        UUID orderId = UUID.randomUUID();
        var command = new ProcessPaymentCommand("corr", orderId, UUID.randomUUID(), new BigDecimal("45000"), "VND");
        int chargesBefore = gateway.totalProcessed();

        // Review tuần 6: bản trước nuốt MỌI exception trong catch rỗng và đếm theo
        // key — nên 4/5 luồng hỏng vì bug, test vẫn xanh. Giờ:
        //  - cổng xuất phát chung (latch) để các luồng thật sự đua nhau ở TX1;
        //  - chỉ CHẤP NHẬN OptimisticLockingFailureException — đó là kết cục hợp
        //    lệ khi hai luồng cùng ghi kết quả ở TX2 (qua Kafka nó sẽ được giao
        //    lại và thấy payment đã xong). Lỗi loại khác = test đỏ;
        //  - đếm TỔNG giao dịch của cổng, không đếm theo key.
        var startGate = new CountDownLatch(1);
        List<Payment> results = new CopyOnWriteArrayList<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        int optimisticLosers;

        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            List<Future<?>> futures = IntStream.range(0, 5).<Future<?>>mapToObj(i -> pool.submit(() -> {
                try {
                    startGate.await();
                    results.add(processPayment.process(command));
                } catch (OptimisticLockingFailureException expected) {
                    // kết cục hợp lệ — đếm ở dưới
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            })).toList();
            startGate.countDown();
            for (var f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        optimisticLosers = 5 - results.size() - unexpected.size();

        assertThat(unexpected).as("không có lỗi nào ngoài xung đột ghi kết quả").isEmpty();
        assertThat(results).as("ít nhất một luồng hoàn tất").isNotEmpty();
        assertThat(results).allSatisfy(p -> assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED));
        assertThat(results.stream().map(Payment::id).collect(Collectors.toSet()))
                .as("mọi luồng dùng chung MỘT payment").hasSize(1);
        assertThat(gateway.totalProcessed() - chargesBefore)
                .as("cổng chỉ xử lý MỘT giao dịch — đếm tổng, không đếm theo key").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(outboxRows(orderId)).as("đúng một event kết quả").isEqualTo(1);
        assertThat(optimisticLosers).isBetween(0, 4);
    }

    // =========================================================================
    // Đơn đã huỷ không bao giờ bị thu tiền (review 25/09)
    // =========================================================================

    @Test
    @DisplayName("order.cancelled tới TRƯỚC payment.requested (payment tụt lại) → không gọi cổng, không phát event")
    void cancellationBeforeRequestNeverCharges() throws Exception {
        UUID orderId = UUID.randomUUID();
        sendCancelled(orderId, OrderCancelledEvent.Reason.PAYMENT_TIMEOUT);
        awaitCancelledRecorded(orderId);

        send(request(orderId, "45000"));   // lệnh thu tiền cũ, giờ mới tới

        Payment payment = awaitPaymentStatus(orderId, PaymentStatus.FAILED);
        assertThat(payment.failureReason()).isEqualTo("ORDER_CANCELLED");
        assertThat(gateway.timesProcessed(payment.id())).as("cổng thanh toán KHÔNG bị gọi").isZero();
        assertThat(outboxRows(orderId)).as("saga đã huỷ đơn — không phát kết quả nào").isZero();
    }

    @Test
    @DisplayName("Lần thử lại sau khi app chết giữa chừng (payment PENDING) gặp bia mộ → không gọi cổng")
    void pendingPaymentOfCancelledOrderIsAbandonedOnRetry() {
        UUID orderId = UUID.randomUUID();
        // Dựng lại: TX1 đã commit PENDING rồi app chết, trước khi gọi cổng.
        Payment pending = payments.save(Payment.initiate(orderId, UUID.randomUUID(), new BigDecimal("45000"), "VND"));
        cancelOrderPayment.onOrderCancelled(orderId, "PAYMENT_TIMEOUT");

        Payment result = processPayment.process(new ProcessPaymentCommand(
                "c", orderId, pending.customerId(), new BigDecimal("45000"), "VND"));

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.id()).as("cùng payment, không tạo cái mới").isEqualTo(pending.id());
        assertThat(gateway.timesProcessed(pending.id())).isZero();
    }

    @Test
    @DisplayName("Huỷ SAU khi đã thu tiền → payment giữ nguyên COMPLETED (cần hoàn tiền — chỉ log), huỷ trùng không lỗi")
    void cancellationAfterCompletedLeavesPayment() {
        UUID orderId = UUID.randomUUID();
        Payment paid = processPayment.process(new ProcessPaymentCommand(
                "c", orderId, UUID.randomUUID(), new BigDecimal("45000"), "VND"));
        assertThat(paid.status()).isEqualTo(PaymentStatus.COMPLETED);

        cancelOrderPayment.onOrderCancelled(orderId, "PAYMENT_TIMEOUT");
        cancelOrderPayment.onOrderCancelled(orderId, "PAYMENT_TIMEOUT");   // giao trùng

        assertThat(payments.findByOrderId(orderId).orElseThrow().status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cancelled_orders WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Huỷ tới ĐÚNG lúc đang gọi cổng → tiền bị trừ, nhưng payment PHẢI log REFUND REQUIRED (review tuần 8)")
    void cancellationDuringGatewayCallIsReportedForRefund(CapturedOutput output) throws Exception {
        UUID orderId = UUID.randomUUID();
        Future<Payment> inFlight = chargeInBackground(orderId);
        awaitGatewayCallInFlight(orderId);

        cancelOrderPayment.onOrderCancelled(orderId, "PAYMENT_TIMEOUT");

        assertThat(inFlight.get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(output).contains("CHARGED order=" + orderId).contains("REFUND REQUIRED");
    }

    @Test
    @DisplayName("Lần giao song song ghi FAILED trong lúc lần này đang gọi cổng → tiền bị trừ thì KHÔNG được im lặng")
    void chargeRecordedAsFailedIsReportedForRefund(CapturedOutput output) throws Exception {
        UUID orderId = UUID.randomUUID();
        Future<Payment> inFlight = chargeInBackground(orderId);
        awaitGatewayCallInFlight(orderId);

        // Giả lập lần giao B: thấy bia mộ và abandon() → FAILED/ORDER_CANCELLED.
        jdbc.update("""
                UPDATE payments SET status = 'FAILED', failure_reason = 'ORDER_CANCELLED', version = version + 1
                WHERE order_id = ?""", orderId);

        Payment recorded = inFlight.get(10, TimeUnit.SECONDS);
        assertThat(recorded.status()).as("không sửa được trạng thái cuối").isEqualTo(PaymentStatus.FAILED);
        assertThat(output).contains("CHARGED but payment").contains("order=" + orderId).contains("REFUND REQUIRED");
    }

    // ---- helper ------------------------------------------------------------

    private final ExecutorService background = Executors.newSingleThreadExecutor();

    private Future<Payment> chargeInBackground(UUID orderId) {
        return background.submit(() -> processPayment.process(new ProcessPaymentCommand(
                "c", orderId, UUID.randomUUID(), new BigDecimal("45000"), "VND")));
    }

    /**
     * Chờ payment PENDING xuất hiện (TX1 đã commit), rồi thêm một nhịp để chắc
     * lần kiểm bia mộ ngay sau TX1 đã qua — lúc này lời gọi cổng (trễ giả lập
     * 200ms) đang bay.
     */
    private void awaitGatewayCallInFlight(UUID orderId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (payments.findByOrderId(orderId).isEmpty() && Instant.now().isBefore(deadline)) {
            Thread.sleep(5);
        }
        assertThat(payments.findByOrderId(orderId)).isPresent();
        Thread.sleep(50);
    }

    private void sendCancelled(UUID orderId, OrderCancelledEvent.Reason reason) throws Exception {
        var envelope = EventEnvelope.of(OrderCancelledEvent.TYPE, orderId.toString(), "c",
                new OrderCancelledEvent(orderId, UUID.randomUUID(), reason));
        kafkaTemplate.send(Topics.ORDER_CANCELLED, orderId.toString(), objectMapper.writeValueAsString(envelope)).get();
    }

    private void awaitCancelledRecorded(UUID orderId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            if (jdbc.queryForObject("SELECT count(*) FROM cancelled_orders WHERE order_id = ?", Integer.class, orderId) == 1) {
                return;
            }
            Thread.sleep(200);
        }
        fail("order %s was never recorded as cancelled", orderId);
    }

    private Payment awaitPaymentStatus(UUID orderId, PaymentStatus status) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            var payment = payments.findByOrderId(orderId);
            if (payment.isPresent() && payment.get().status() == status) {
                return payment.get();
            }
            Thread.sleep(200);
        }
        return fail("payment of order %s never reached %s", orderId, status);
    }

    private static EventEnvelope<PaymentRequestedEvent> request(UUID orderId, String amount) {
        var payload = new PaymentRequestedEvent(orderId, UUID.randomUUID(), new BigDecimal(amount), "VND");
        return EventEnvelope.of(PaymentRequestedEvent.TYPE, orderId.toString(), "corr-" + orderId, payload);
    }

    private void send(EventEnvelope<PaymentRequestedEvent> envelope) throws Exception {
        send(envelope, envelope.aggregateId());
    }

    private void send(EventEnvelope<PaymentRequestedEvent> envelope, String key) throws Exception {
        kafkaTemplate.send(Topics.PAYMENT_REQUESTED, key, objectMapper.writeValueAsString(envelope)).get();
    }

    private Integer outboxRows(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, orderId.toString());
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            var found = seen.stream().filter(r -> r.topic().equals(topic) && key.equals(r.key())).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            resultConsumer.poll(Duration.ofMillis(500)).forEach(seen::add);
        }
        return fail("No record on %s with key %s within 30s", topic, key);
    }

    private long countSeen(String topic, String key) {
        resultConsumer.poll(Duration.ofSeconds(1)).forEach(seen::add);
        return seen.stream().filter(r -> r.topic().equals(topic) && key.equals(r.key())).count();
    }
}
