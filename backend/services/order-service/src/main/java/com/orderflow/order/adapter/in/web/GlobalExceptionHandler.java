package com.orderflow.order.adapter.in.web;

import com.orderflow.order.adapter.in.web.response.ErrorResponse;
import com.orderflow.order.domain.exception.InvalidOrderStateException;
import com.orderflow.order.domain.exception.OrderNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    /** Domain tự validate và ném IllegalArgumentException — vẫn là lỗi của client. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "INVALID_ARGUMENT", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);   // log đầy đủ ở server
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "INTERNAL_ERROR",
                        "An unexpected error occurred"));   // nhưng không lộ ra ngoài
    }
}
