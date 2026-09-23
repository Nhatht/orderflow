package com.orderflow.order.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Nhận {@code X-Correlation-Id} do API Gateway sinh ở rìa hệ thống, đặt vào MDC.
 *
 * <p>Từ đây, MỌI dòng log trong request này mang correlationId, và
 * {@code OutboxEventPublisher.publishOrderCreated} đọc lại nó cho
 * {@code order.created} — sợi chỉ nối request HTTP của khách với toàn bộ saga
 * phía sau, xuyên qua bốn service.
 *
 * <p><b>Vì sao MDC chứ không truyền qua {@code PlaceOrderCommand}:</b>
 * correlationId là siêu dữ liệu hạ tầng (để lần vết), không phải dữ liệu
 * nghiệp vụ. Nhét vào command thì mọi use case, mọi test đều phải mang theo
 * một thứ mà logic nghiệp vụ không bao giờ đọc. MDC là "ngữ cảnh của luồng
 * đang chạy" — đúng chỗ của nó. Listener Kafka đã dùng MDC y hệt từ tuần 4.
 *
 * <p>Không có header (gọi thẳng service, không qua gateway) thì tự sinh — mọi
 * request đều có correlationId.
 *
 * <p>Header đến từ bên ngoài nên được KIỂM TRA: chỉ nhận chuỗi ngắn gồm chữ,
 * số và gạch ngang. Không kiểm tra thì client gửi được giá trị có xuống dòng
 * vào log (log injection) hoặc chuỗi dài vài MB.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String correlationId = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);   // client dùng id này khi báo lỗi
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);   // thread của Tomcat được tái sử dụng
        }
    }
}
