package com.orderflow.inventory.adapter.in.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Định dạng lỗi thống nhất cho toàn bộ API.
 *
 * <p>Dùng một shape duy nhất cho mọi lỗi để client chỉ phải viết một
 * bộ xử lý — thay vì đoán xem lỗi này trả về kiểu gì.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Standard error response")
public record ErrorResponse(

        @Schema(example = "2026-09-23T01:00:00Z")
        Instant timestamp,

        @Schema(example = "400")
        int status,

        @Schema(example = "VALIDATION_FAILED")
        String code,

        @Schema(example = "Request validation failed")
        String message,

        @Schema(description = "Chi tiết từng field sai, chỉ có với lỗi validate")
        List<FieldError> errors
) {

    public record FieldError(String field, String message) {}

    public static ErrorResponse of(int status, String code, String message) {
        return new ErrorResponse(Instant.now(), status, code, message, null);
    }

    public static ErrorResponse of(int status, String code, String message, List<FieldError> errors) {
        return new ErrorResponse(Instant.now(), status, code, message, errors);
    }
}
