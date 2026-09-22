package com.orderflow.order.adapter.in.web;

import com.orderflow.order.adapter.in.web.request.PlaceOrderRequest;
import com.orderflow.order.adapter.in.web.response.ErrorResponse;
import com.orderflow.order.application.dto.OrderView;
import com.orderflow.order.application.port.in.GetOrderQuery;
import com.orderflow.order.application.port.in.PlaceOrderUseCase;
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
            UriComponentsBuilder uriBuilder) {

        OrderView order = placeOrderUseCase.placeOrder(request.toCommand());

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
    public OrderView getOrder(@PathVariable UUID orderId) {
        return getOrderQuery.getById(orderId);
    }

    @GetMapping
    @Operation(summary = "List orders of a customer", description = "Newest first")
    @ResponseStatus(HttpStatus.OK)
    public List<OrderView> getCustomerOrders(@RequestParam UUID customerId) {
        return getOrderQuery.getByCustomer(customerId);
    }
}
