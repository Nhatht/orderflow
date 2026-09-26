package com.orderflow.order.adapter.in.web;

import com.orderflow.order.adapter.in.web.request.PlaceOrderRequest;
import com.orderflow.order.adapter.in.web.response.ErrorResponse;
import com.orderflow.order.application.dto.OrderView;
import com.orderflow.order.application.dto.SagaView;
import com.orderflow.order.application.port.in.GetOrderQuery;
import com.orderflow.order.application.port.in.GetSagaQuery;
import com.orderflow.order.application.port.in.PlaceOrderUseCase;
import com.orderflow.order.domain.exception.OrderNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST adapter — CỔNG VÀO của hệ thống.
 *
 * <p>Controller phụ thuộc vào INTERFACE use case ({@link PlaceOrderUseCase},
 * {@link GetOrderQuery}), không phụ thuộc class cài đặt. Nhờ vậy nó không biết
 * gì về JPA, Kafka hay bất kỳ hạ tầng nào.
 *
 * <p>Controller phải MỎNG: nhận request, dịch sang command, gọi use case,
 * trả response. Không có một dòng logic nghiệp vụ nào ở đây.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Order placement and retrieval")
public class OrderController {

    private final PlaceOrderUseCase placeOrderUseCase;
    private final GetOrderQuery getOrderQuery;
    private final GetSagaQuery getSagaQuery;

    @PostMapping
    @Operation(
            summary = "Place a new order",
            description = "Creates an order in PENDING status. From week 6 this also starts the saga.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order created"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<OrderView> placeOrder(
            @Valid @RequestBody PlaceOrderRequest request,
            @RequestHeader(name = CUSTOMER_HEADER, required = false) UUID authenticatedCustomer,
            UriComponentsBuilder uriBuilder) {

        OrderView order = placeOrderUseCase.placeOrder(request.toCommand(resolveCustomer(authenticatedCustomer, request.customerId())));

        // 201 Created kèm header Location trỏ tới tài nguyên vừa tạo — đúng chuẩn REST.
        URI location = uriBuilder.path("/api/orders/{id}")
                .buildAndExpand(order.id())
                .toUri();

        return ResponseEntity.created(location).body(order);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get an order by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order found"),
            @ApiResponse(responseCode = "404", description = "Order not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public OrderView getOrder(@PathVariable UUID orderId,
                              @RequestHeader(name = CUSTOMER_HEADER, required = false) UUID authenticatedCustomer) {
        return ownedOrder(orderId, authenticatedCustomer);
    }

    @GetMapping("/{orderId}/saga")
    @Operation(summary = "Get the saga of an order",
            description = "Current saga status plus every step in the order it happened, "
                        + "including compensating steps. Source of the saga timeline in the UI.")
    public SagaView getSaga(@PathVariable UUID orderId,
                            @RequestHeader(name = CUSTOMER_HEADER, required = false) UUID authenticatedCustomer) {
        ownedOrder(orderId, authenticatedCustomer);
        return getSagaQuery.getByOrderId(orderId);
    }

    @GetMapping
    @Operation(summary = "List orders of a customer", description = "Newest first")
    @ResponseStatus(HttpStatus.OK)
    public List<OrderView> getCustomerOrders(
            @RequestParam(required = false) UUID customerId,
            @RequestHeader(name = CUSTOMER_HEADER, required = false) UUID authenticatedCustomer) {
        return getOrderQuery.getByCustomer(resolveCustomer(authenticatedCustomer, customerId));
    }

    // ---- Danh tính khách hàng (tuần 7) --------------------------------------

    /** Header do API Gateway gắn, lấy từ claim trong JWT đã xác thực. */
    static final String CUSTOMER_HEADER = "X-Customer-Id";

    /**
     * Chống IDOR: đoán được orderId của người khác thì cũng không xem được đơn —
     * hay BẤT CỨ thứ gì treo dưới đơn đó (saga...). Mọi endpoint có
     * {@code {orderId}} trong đường dẫn phải đi qua đây.
     *
     * <p>Trả 404 chứ không 403 — 403 xác nhận "đơn này có tồn tại".
     */
    private OrderView ownedOrder(UUID orderId, UUID authenticatedCustomer) {
        OrderView order = getOrderQuery.getById(orderId);
        if (authenticatedCustomer != null && !authenticatedCustomer.equals(order.customerId())) {
            throw new OrderNotFoundException(orderId);
        }
        return order;
    }

    /**
     * Khách hàng là ai: tin HEADER của gateway, KHÔNG tin body/query của client.
     *
     * <p>Trước tuần 7, client tự khai {@code customerId} trong body — ai cũng đặt
     * đơn được dưới tên người khác. Giờ gateway xác thực JWT rồi gắn
     * {@code X-Customer-Id} (và XOÁ header cùng tên nếu client tự gửi lên). Có
     * header thì nó thắng, giá trị client khai bị bỏ qua.
     *
     * <p>Không có header — gọi thẳng service, không qua gateway (test, dev) —
     * thì dùng giá trị client khai để tương thích ngược. Điều này chỉ an toàn
     * khi service KHÔNG lộ ra ngoài, mọi traffic từ internet đều phải qua
     * gateway. Kiến trúc zero-trust sẽ bắt từng service tự kiểm tra JWT; ở đây
     * chấp nhận tin mạng nội bộ — đơn giản hoá có chủ đích, ghi rõ.
     */
    private static UUID resolveCustomer(UUID fromGateway, UUID claimedByClient) {
        if (fromGateway != null) {
            return fromGateway;
        }
        if (claimedByClient == null) {
            throw new IllegalArgumentException("customerId is required (or call through the API Gateway)");
        }
        return claimedByClient;
    }
}
