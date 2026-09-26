package com.orderflow.gateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TUẦN 7 — API Gateway: JWT, gắn danh tính, correlationId, rate limit.
 *
 * <p>Redis THẬT (rate limiter chạy script Lua trên Redis — mock không kiểm
 * chứng được gì). Service phía sau là một HTTP server tí hon của JDK, chỉ ghi
 * lại header nó nhận được: thứ cần kiểm chứng là gateway GỬI XUỐNG cái gì.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    static final AtomicReference<Headers> LAST_DOWNSTREAM_HEADERS = new AtomicReference<>();
    static final HttpServer FAKE_ORDER_SERVICE;

    static {
        REDIS.start();
        try {
            FAKE_ORDER_SERVICE = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE_ORDER_SERVICE.createContext("/", exchange -> {
            LAST_DOWNSTREAM_HEADERS.set(exchange.getRequestHeaders());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            // Như CorrelationIdFilter của order-service thật: trả lại correlation id.
            String correlationId = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
            if (correlationId != null) {
                exchange.getResponseHeaders().set("X-Correlation-Id", correlationId);
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        FAKE_ORDER_SERVICE.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("ORDER_SERVICE_URL", () -> "http://localhost:" + FAKE_ORDER_SERVICE.getAddress().getPort());
        registry.add("INVENTORY_SERVICE_URL", () -> "http://localhost:" + FAKE_ORDER_SERVICE.getAddress().getPort());
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:" + FAKE_ORDER_SERVICE.getAddress().getPort());
        // Xô nhỏ để test thấy 429 nhanh: tối đa 3 request dồn, đổ lại 1 token/giây.
        registry.add("orderflow.gateway.rate-limit.replenish-rate", () -> "1");
        registry.add("orderflow.gateway.rate-limit.burst-capacity", () -> "3");
    }

    @Autowired WebTestClient client;

    private String login(String user, String password) {
        Map<?, ?> body = client.post().uri("/auth/login")
                .bodyValue(Map.of("username", user, "password", password))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("accessToken");
    }

    // =========================================================================

    @Test
    @DisplayName("Không token → 401, không chạm tới service phía sau")
    void requestWithoutTokenIsRejected() {
        client.get().uri("/api/orders/123").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Sai mật khẩu → 401; token bị sửa chữ ký → 401")
    void badCredentialsAndTamperedTokenAreRejected() {
        client.post().uri("/auth/login").bodyValue(Map.of("username", "alice", "password", "wrong"))
                .exchange().expectStatus().isUnauthorized();

        String token = login("alice", "alice123");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        client.get().uri("/api/orders/123").header("Authorization", "Bearer " + tampered)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Gateway gắn X-Customer-Id từ JWT, GHI ĐÈ giá trị client tự gửi, và gắn X-Correlation-Id")
    void propagatesIdentityAndCorrelationId() {
        String token = login("alice", "alice123");

        var response = client.get().uri("/api/orders/123")
                .header("Authorization", "Bearer " + token)
                .header("X-Customer-Id", "00000000-0000-0000-0000-000000000666")   // thử mạo danh
                .exchange().expectStatus().isOk()
                .returnResult(String.class);

        Headers downstream = LAST_DOWNSTREAM_HEADERS.get();
        assertThat(downstream.get("X-Customer-Id"))
                .as("chỉ còn đúng một giá trị — của token, không phải của client")
                .containsExactly("c0ffee00-0000-0000-0000-00000000a11c");
        String correlationId = downstream.getFirst("X-Correlation-Id");
        assertThat(correlationId).isNotBlank();
        assertThat(response.getResponseHeaders().get("X-Correlation-Id"))
                .as("đúng MỘT giá trị — gateway và service phía sau cùng đặt header này")
                .containsExactly(correlationId);
    }

    @Test
    @DisplayName("Lệnh nội bộ của inventory, API gỡ lỗi của payment và endpoint quản lý route KHÔNG lọt ra ngoài, kể cả có token")
    void internalEndpointsAreNotExposed() {
        String token = login("alice", "alice123");

        client.get().uri("/api/inventory/stock/abc").header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isOk();

        for (String path : new String[] {"/api/inventory/reservations",
                "/api/inventory/reservations/release", "/api/inventory/reservations/confirm"}) {
            client.post().uri(path).header("Authorization", "Bearer " + token).bodyValue("{}")
                    .exchange().expectStatus().isNotFound();
        }

        client.get().uri("/api/payments/by-order/abc").header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isNotFound();

        client.get().uri("/actuator/gateway/routes").header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isNotFound();
        client.post().uri("/actuator/gateway/refresh").header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Dồn dập vượt token bucket → một phần nhận 429; mỗi người dùng một xô riêng")
    void burstIsRateLimitedPerUser() {
        String bob = login("bob", "bob123");

        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 10; i++) {
            HttpStatus status = (HttpStatus) client.get().uri("/api/orders/x")
                    .header("Authorization", "Bearer " + bob)
                    .exchange().returnResult(String.class).getStatus();
            if (status == HttpStatus.OK) ok++;
            if (status == HttpStatus.TOO_MANY_REQUESTS) limited++;
        }

        assertThat(ok).as("xô 3 token + đổ lại 1/giây").isBetween(3, 5);
        assertThat(limited).as("phần vượt bị chặn").isGreaterThanOrEqualTo(5);

        // Bob cạn xô không ảnh hưởng Alice.
        client.get().uri("/api/orders/x").header("Authorization", "Bearer " + login("alice", "alice123"))
                .exchange().expectStatus().isOk();
    }
}
