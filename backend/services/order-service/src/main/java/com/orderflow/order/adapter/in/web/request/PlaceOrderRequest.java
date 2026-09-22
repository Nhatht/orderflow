package com.orderflow.order.adapter.in.web.request;

import com.orderflow.order.application.dto.PlaceOrderCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Body của POST /api/orders.
 *
 * <p>Validate ở đây là lớp PHÒNG THỦ ĐẦU TIÊN, không phải lớp duy nhất — domain
 * model vẫn tự kiểm tra lại. Lý do: request chỉ chặn được đầu vào từ HTTP, còn
 * domain phải đúng dù được gọi từ Kafka listener, test, hay bất cứ đâu.
 *
 * <p>Khác biệt là ở trải nghiệm: bắt lỗi tại đây trả về 400 kèm danh sách field
 * sai; để lọt xuống domain thì thành exception và khó diễn giải cho client.
 */
@Schema(description = "Request to place a new order")
public record PlaceOrderRequest(

        @NotNull(message = "customerId is required")
        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID customerId,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
        @Schema(example = "VND")
        String currency,

        @NotEmpty(message = "order must contain at least one item")
        @Valid
        List<Item> items
) {

    @Schema(description = "A single line item")
    public record Item(

            @NotNull(message = "productId is required")
            UUID productId,

            @NotBlank(message = "productName is required")
            @Size(max = 255)
            String productName,

            @Positive(message = "quantity must be positive")
            int quantity,

            @NotNull(message = "unitPrice is required")
            @PositiveOrZero(message = "unitPrice must not be negative")
            @Digits(integer = 15, fraction = 4, message = "unitPrice allows at most 4 decimal places")
            BigDecimal unitPrice
    ) {}

    /** Dịch hợp đồng HTTP sang hợp đồng nghiệp vụ. */
    public PlaceOrderCommand toCommand() {
        List<PlaceOrderCommand.Item> commandItems = items.stream()
                .map(i -> new PlaceOrderCommand.Item(
                        i.productId(), i.productName(), i.quantity(), i.unitPrice()))
                .toList();

        return new PlaceOrderCommand(customerId, currency, commandItems);
    }
}
