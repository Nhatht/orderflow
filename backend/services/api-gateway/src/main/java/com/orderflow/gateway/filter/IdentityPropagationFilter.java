package com.orderflow.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gắn DANH TÍNH và CORRELATION ID vào mọi request trước khi chuyển xuống service.
 *
 * <p><b>Danh tính:</b> service phía sau KHÔNG tự kiểm JWT — chúng tin header
 * {@code X-Customer-Id} do gateway gắn, lấy từ claim của token ĐÃ xác thực.
 * Vì vậy việc đầu tiên là XOÁ mọi {@code X-Customer-Id} client tự gửi lên:
 * không xoá thì ai cũng mạo danh được người khác chỉ bằng một header.
 *
 * <p><b>Correlation ID:</b> sinh MỘT lần ở rìa, đi theo request vào
 * order-service, vào event {@code order.created}, rồi qua cả saga. Client tự gửi
 * thì giữ nguyên (để nối log phía client), miễn là chuỗi an toàn — chặn log
 * injection. Trả lại trong response để khách báo lỗi kèm mã này.
 *
 * <p>Chạy sau Spring Security (security là WebFilter, chạy trước mọi
 * GlobalFilter), nên principal đã sẵn sàng.
 */
@Component
public class IdentityPropagationFilter implements GlobalFilter, Ordered {

    public static final String CUSTOMER_HEADER = "X-Customer-Id";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(CORRELATION_HEADER);
        String correlationId = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set(CORRELATION_HEADER, correlationId);

        return exchange.getPrincipal()
                .map(principal -> customerIdOf(principal))
                .defaultIfEmpty("")
                .flatMap(customerId -> {
                    // Chép ra bản GHI ĐƯỢC. Header của request gốc là read-only;
                    // mutate().headers(...) cũng trả về bản read-only ở đây nên
                    // remove() ném UnsupportedOperationException (đã gặp: 500).
                    HttpHeaders headers = new HttpHeaders();
                    headers.putAll(exchange.getRequest().getHeaders());
                    headers.remove(CUSTOMER_HEADER);                // chặn mạo danh
                    headers.set(CORRELATION_HEADER, correlationId);
                    if (!customerId.isEmpty()) {
                        headers.set(CUSTOMER_HEADER, customerId);
                    }
                    ServerHttpRequest request = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public HttpHeaders getHeaders() {
                            return headers;
                        }
                    };
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }

    private static String customerIdOf(Object principal) {
        if (principal instanceof JwtAuthenticationToken jwt) {
            String claim = jwt.getToken().getClaimAsString("customerId");
            return claim == null ? "" : claim;
        }
        return "";
    }

    /** Chạy sớm, trước các filter định tuyến của gateway. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
