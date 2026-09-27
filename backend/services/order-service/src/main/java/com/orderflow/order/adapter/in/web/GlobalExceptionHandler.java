package com.orderflow.order.adapter.in.web;

import com.orderflow.order.adapter.in.web.response.ErrorResponse;
import com.orderflow.order.domain.exception.InvalidOrderStateException;
import com.orderflow.order.domain.exception.OrderNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * Dịch exception thành HTTP response.
 *
 * <p>Gom một chỗ để controller không phải try/catch, và để mọi lỗi ra ngoài
 * đều cùng một định dạng.
 *
 * <p><b>Nguyên tắc bảo mật:</b> không bao giờ để stack trace hay thông điệp
 * lỗi gốc lọt ra client ở nhánh 500 — chúng lộ tên bảng, câu SQL, phiên bản
 * thư viện. Log đầy đủ ở server, trả ra ngoài thông điệp chung chung.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(OrderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "ORDER_NOT_FOUND", ex.getMessage()));
    }

    /**
     * 409 Conflict chứ không phải 400: request hợp lệ về mặt cú pháp, chỉ là
     * xung đột với trạng thái hiện tại của tài nguyên.
     */
    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(InvalidOrderStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "INVALID_ORDER_STATE", ex.getMessage()));
    }

    /**
     * Có người khác sửa cùng bản ghi trước ta (xem {@code @Version} ở
     * {@code OrderJpaEntity}). 409 là đúng, và client nên đọc lại rồi thử lại.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "CONCURRENT_MODIFICATION",
                        "The order was modified by another request. Please retry."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();

        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "VALIDATION_FAILED",
                        "Request validation failed", errors));
    }

    /**
     * Body không đọc được: JSON sai cú pháp, sai kiểu, hoặc không phải UTF-8 hợp lệ.
     *
     * <p>Đây là lỗi của client nên phải trả 400. Không có handler này thì nó rơi
     * xuống nhánh 500 chung — khiến client tưởng server hỏng trong khi lỗi nằm ở
     * request của họ, và làm nhiễu cảnh báo vận hành.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Malformed request body: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "MALFORMED_REQUEST",
                        "Request body is not valid JSON"));
    }

    /** Domain tự validate và ném IllegalArgumentException — vẫn là lỗi của client. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "INVALID_ARGUMENT", ex.getMessage()));
    }

    /**
     * Tham số trên URL sai kiểu: {@code GET /api/orders/abc} ({@code orderId} phải là
     * UUID), {@code ?customerId=abc}. Lỗi của client → 400, không phải 500.
     *
     * <p>Không có handler này thì Spring bọc {@code IllegalArgumentException} của
     * {@code UUID.fromString} trong {@code MethodArgumentTypeMismatchException},
     * handler {@code IllegalArgumentException} ở trên KHÔNG bắt được, và lỗi rơi
     * xuống nhánh 500. Không lặp lại giá trị client gửi trong thông điệp.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Class<?> required = ex.getRequiredType();
        String expected = required == null ? "value" : required.getSimpleName();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "INVALID_PARAMETER",
                        "Parameter '%s' must be a valid %s".formatted(ex.getName(), expected)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // Lỗi chuẩn của Spring MVC (route không tồn tại → 404, sai method → 405,
        // thiếu tham số → 400...) tự mang sẵn mã HTTP đúng. Nhánh bắt-tất-cả này
        // không được biến chúng thành 500.
        if (ex instanceof org.springframework.web.ErrorResponse framework) {
            int status = framework.getStatusCode().value();
            HttpStatus known = HttpStatus.resolve(status);
            String code = known != null ? known.name() : "HTTP_" + status;
            String message = known != null ? known.getReasonPhrase() : "Request failed";
            return ResponseEntity.status(status).body(ErrorResponse.of(status, code, message));
        }
        log.error("Unhandled exception", ex);   // log đầy đủ ở server
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "INTERNAL_ERROR",
                        "An unexpected error occurred"));   // nhưng không lộ ra ngoài
    }
}
