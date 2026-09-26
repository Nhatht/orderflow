package com.orderflow.inventory.integration;

import com.orderflow.contracts.Topics;
import com.orderflow.inventory.application.dto.StockView;
import com.orderflow.inventory.application.port.out.StockRepositoryPort;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Nền chung cho mọi integration test của inventory: PostgreSQL + Redis + Kafka THẬT.
 *
 * <p><b>Singleton container pattern.</b> Container khởi động MỘT lần trong
 * khối {@code static} và dùng chung cho mọi class test con, thay vì
 * {@code @Container} ở từng class (mỗi class dựng lại ba container, mất thêm
 * ~15 giây mỗi lần). Testcontainers tự dọn chúng khi JVM tắt (container Ryuk).
 *
 * <p>Lợi ích thứ hai, ít người để ý: các class con có cấu hình giống hệt nhau
 * nên Spring tái sử dụng CÙNG một application context đã cache. Nếu mỗi class
 * có container riêng, cổng khác nhau → cấu hình khác nhau → Spring dựng lại
 * context từ đầu cho từng class.
 *
 * <p>Hệ quả phải nhớ: database và topic dùng chung giữa MỌI test. Mỗi test tự
 * tạo sản phẩm và orderId riêng, không bao giờ dựa vào dữ liệu seed hay vào
 * thứ tự chạy.
 */
@SpringBootTest
public abstract class AbstractInventoryIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("inventory_db")
            .withUsername("orderflow")
            .withPassword("orderflow");

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
        createTopicsOwnedByOrderService();
    }

    /**
     * {@code order.created/confirmed/cancelled} do order-service tạo trong hệ thống thật. Ở đây
     * không có order-service, nên test tạo thay — cùng số partition với cấu
     * hình thật, để hành vi phân chia partition giống production.
     */
    private static void createTopicsOwnedByOrderService() {
        try (var admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(Topics.ORDER_CREATED, 3, (short) 1),
                    new NewTopic(Topics.ORDER_CONFIRMED, 3, (short) 1),
                    new NewTopic(Topics.ORDER_CANCELLED, 3, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create order-service topics", e);
        }
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // Job hết hạn tự chạy mỗi 30 giây sẽ chen vào giữa test và nhả phiếu
        // mà test đang cố tình làm quá hạn. Đẩy chu kỳ ra xa; test gọi thẳng
        // ExpireReservationsUseCase với "now" do nó kiểm soát.
        registry.add("orderflow.inventory.expiry.interval", () -> "PT1H");
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StockRepositoryPort stockRepository;

    /**
     * Đọc tồn kho THẲNG từ database, bỏ qua cache. Test kiểm SỰ THẬT sau khi
     * giữ/nhả/chốt; còn {@code GetStockQuery} giờ đi qua cache (pattern 6) và
     * có thể trả số cũ vài trăm ms trước khi event xoá cache tới — dùng nó để
     * kiểm tồn kho sẽ làm test chập chờn. Cache có test riêng: {@code StockCacheIT}.
     */
    protected StockView stockInDb(UUID productId) {
        return stockRepository.findByProductId(productId).map(StockView::from).orElseThrow();
    }

    /** Tạo một sản phẩm mới với tồn kho cho trước, riêng cho từng test. */
    protected UUID givenProductWithStock(int quantity) {
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, sku, name, price, currency) VALUES (?, ?, ?, ?, ?)",
                productId, "TEST-" + productId.toString().substring(0, 8),
                "Test product", new BigDecimal("10000.0000"), "VND");
        jdbc.update("INSERT INTO stock (product_id, available_qty, reserved_qty) VALUES (?, ?, 0)",
                productId, quantity);
        return productId;
    }
}
