package com.orderflow.inventory.adapter.in.web;

import com.orderflow.inventory.adapter.in.web.request.ReserveStockRequest;
import com.orderflow.inventory.application.dto.ReservationView;
import com.orderflow.inventory.application.dto.ProductView;
import com.orderflow.inventory.application.dto.StockView;
import com.orderflow.inventory.application.port.in.GetStockQuery;
import com.orderflow.inventory.application.port.in.ListProductsQuery;
import com.orderflow.inventory.application.port.in.ReserveStockUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST adapter.
 *
 * <p>Từ tuần 4, các thao tác này sẽ được gọi chủ yếu qua Kafka listener
 * (do saga điều phối). REST endpoint vẫn giữ để test tay và gỡ lỗi.
 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@Tag(name = "Inventory", description = "Stock reservation with distributed locking")
public class InventoryController {

    private final ReserveStockUseCase reserveStockUseCase;
    private final GetStockQuery getStockQuery;
    private final ListProductsQuery listProductsQuery;

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Reserve stock",
            description = "Idempotent: calling again with the same (orderId, productId) "
                        + "returns the existing reservation instead of reserving twice.")
    public ReservationView reserve(@Valid @RequestBody ReserveStockRequest request) {
        return reserveStockUseCase.reserve(request.toCommand());
    }

    @PostMapping("/reservations/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Release a reservation", description = "Saga compensating action. Idempotent.")
    public void release(@RequestParam UUID orderId, @RequestParam UUID productId) {
        reserveStockUseCase.release(orderId, productId);
    }

    @PostMapping("/reservations/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirm a reservation", description = "Stock physically leaves. Idempotent.")
    public void confirm(@RequestParam UUID orderId, @RequestParam UUID productId) {
        reserveStockUseCase.confirm(orderId, productId);
    }

    @GetMapping("/stock/{productId}")
    @Operation(
            summary = "Get stock level",
            description = "Returns both available and reserved quantities, so the UI can "
                        + "distinguish a genuine stock-out from stock held by another order.")
    public StockView getStock(@PathVariable UUID productId) {
        return getStockQuery.getByProductId(productId);
    }

    @GetMapping("/products")
    @Operation(
            summary = "List products for the catalog",
            description = "Every product with its price and the quantity still available to buy. "
                        + "Not cached: availability changes on every reservation.")
    public List<ProductView> listProducts() {
        return listProductsQuery.listProducts();
    }
}
