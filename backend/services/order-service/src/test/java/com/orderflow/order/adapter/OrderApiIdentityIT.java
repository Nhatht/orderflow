package com.orderflow.order.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.EventEnvelope;
import com.orderflow.contracts.order.OrderCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TUẦN 7 — order-service tin danh tính và correlationId do API Gateway gắn.
 *
 * <p>Gọi qua HTTP (MockMvc) vì thứ đang kiểm chứng nằm ở tầng web: header,
 * filter, controller. Gateway không có ở đây — test tự gắn header y như
 * gateway sẽ gắn.
 */
@AutoConfigureMockMvc
class OrderApiIdentityIT extends AbstractOrderIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    private static String body(UUID customerIdInBody) {
        String customer = customerIdInBody == null ? "" : "\"customerId\":\"%s\",".formatted(customerIdInBody);
        return """
                {%s"currency":"VND","items":[{"productId":"%s","productName":"Keo dua","quantity":1,"unitPrice":45000}]}
                """.formatted(customer, UUID.randomUUID());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Header của gateway THẮNG customerId client tự khai trong body")
    void gatewayIdentityOverridesBody() throws Exception {
        UUID fromToken = UUID.randomUUID();
        UUID claimed = UUID.randomUUID();

        MvcResult result = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Customer-Id", fromToken).content(body(claimed)))
                .andExpect(status().isCreated()).andReturn();

        assertThat(json(result).get("customerId").asText()).isEqualTo(fromToken.toString());
    }

    @Test
    @DisplayName("Không header, không customerId → 400")
    void missingIdentityIsRejected() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body(null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Xem đơn của người khác → 404 (không phải 403 — không tiết lộ đơn tồn tại)")
    void cannotReadSomeoneElsesOrder() throws Exception {
        UUID owner = UUID.randomUUID();
        MvcResult created = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Customer-Id", owner).content(body(null)))
                .andExpect(status().isCreated()).andReturn();
        String orderId = json(created).get("id").asText();

        mvc.perform(get("/api/orders/{id}", orderId).header("X-Customer-Id", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/{id}", orderId).header("X-Customer-Id", owner))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Xem saga của đơn người khác → 404; thêm /saga vào đường dẫn không lách được")
    void cannotReadSomeoneElsesSaga() throws Exception {
        UUID owner = UUID.randomUUID();
        MvcResult created = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Customer-Id", owner).content(body(null)))
                .andExpect(status().isCreated()).andReturn();
        String orderId = json(created).get("id").asText();

        mvc.perform(get("/api/orders/{id}/saga", orderId).header("X-Customer-Id", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/{id}/saga", orderId).header("X-Customer-Id", owner))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("orderId không phải UUID → 400 INVALID_PARAMETER (trước đây rơi xuống 500)")
    void nonUuidOrderIdIsBadRequest() throws Exception {
        UUID customer = UUID.randomUUID();

        for (String path : new String[]{"/api/orders/not-a-uuid", "/api/orders/not-a-uuid/saga"}) {
            MvcResult result = mvc.perform(get(path).header("X-Customer-Id", customer))
                    .andExpect(status().isBadRequest()).andReturn();
            JsonNode error = json(result);
            assertThat(error.get("status").asInt()).isEqualTo(400);
            assertThat(error.get("code").asText()).isEqualTo("INVALID_PARAMETER");
            assertThat(error.get("message").asText()).contains("orderId").doesNotContain("not-a-uuid");
        }

        mvc.perform(get("/api/orders").param("customerId", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Lỗi chuẩn của Spring MVC giữ đúng mã HTTP: route lạ → 404, sai method → 405, không phải 500")
    void frameworkErrorsKeepTheirStatus() throws Exception {
        MvcResult notFound = mvc.perform(get("/api/orders/{id}/no-such-thing", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(json(notFound).get("code").asText()).isEqualTo("NOT_FOUND");

        mvc.perform(delete("/api/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("X-Correlation-Id từ gateway chảy vào order.created — nối request HTTP với cả saga")
    void correlationIdFlowsIntoOrderCreated() throws Exception {
        try (var consumer = orderCreatedConsumer()) {
            MvcResult result = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Customer-Id", UUID.randomUUID())
                            .header("X-Correlation-Id", "gw-7f3a-test")
                            .content(body(null)))
                    .andExpect(status().isCreated()).andReturn();

            assertThat(result.getResponse().getHeader("X-Correlation-Id")).isEqualTo("gw-7f3a-test");

            String orderId = json(result).get("id").asText();
            EventEnvelope<OrderCreatedEvent> envelope = objectMapper.readValue(
                    awaitRecordWithKey(consumer, orderId, Duration.ofSeconds(20)).value(), new TypeReference<>() {});
            assertThat(envelope.correlationId()).isEqualTo("gw-7f3a-test");
        }
    }

    @Test
    @DisplayName("Correlation id độc hại (xuống dòng — log injection) bị thay bằng id sinh mới")
    void unsafeCorrelationIdIsReplaced() throws Exception {
        MvcResult result = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Customer-Id", UUID.randomUUID())
                        .header("X-Correlation-Id", "abc\nFAKE LOG LINE")
                        .content(body(null)))
                .andExpect(status().isCreated()).andReturn();

        String used = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(used).doesNotContain("\n").doesNotContain("FAKE");
        assertThat(UUID.fromString(used)).isNotNull();
    }
}
