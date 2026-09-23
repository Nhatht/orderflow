package com.orderflow.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Giới hạn tần suất THEO NGƯỜI DÙNG, không theo IP.
 *
 * <p>{@code RequestRateLimiter} của Spring Cloud Gateway dùng thuật toán
 * <b>token bucket</b> trên Redis: mỗi khoá có một xô chứa tối đa
 * {@code burstCapacity} token, được đổ thêm {@code replenishRate} token mỗi
 * giây; mỗi request lấy một token, xô rỗng thì trả 429. Nhờ vậy cho phép dồn
 * dập ngắn (tới burstCapacity) nhưng chặn tốc độ trung bình vượt ngưỡng.
 *
 * <p><b>Vì sao đếm ở Redis chứ không trong RAM gateway:</b> chạy 3 instance
 * gateway sau load balancer thì mỗi cái đếm riêng → giới hạn thật thành gấp 3.
 * Redis là bộ đếm chung. Script Lua chạy nguyên tử nên không có race giữa đọc
 * và trừ token.
 *
 * <p><b>Vì sao theo người dùng:</b> theo IP thì cả văn phòng sau một NAT chung
 * bị chặn vì một người; và một người đổi IP liên tục thì lách được. Chưa đăng
 * nhập (không có principal) thì mới rơi về IP.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> "user:" + principal.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                        .map(InetSocketAddress::getHostString)
                        .orElse("unknown")));
    }
}
