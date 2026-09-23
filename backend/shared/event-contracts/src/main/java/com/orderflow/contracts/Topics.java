package com.orderflow.contracts;

/**
 * Tên các Kafka topic.
 *
 * <p><b>Vì sao đặt ở shared module</b> dù nguyên tắc là "chỉ để event DTO":
 * tên topic là một phần của hợp đồng giao tiếp, y như tên field trong event.
 * Producer gõ {@code "order.created"}, consumer gõ {@code "order-created"} —
 * không ai báo lỗi, message cứ thế rơi vào khoảng không. Để chung một hằng số
 * thì sai tên là lỗi biên dịch.
 *
 * <p><b>Vì sao là class hằng số chứ không phải enum:</b> {@code @KafkaListener(topics = ...)}
 * đòi giá trị hằng lúc biên dịch. Enum không dùng được trong annotation dạng String.
 *
 * <p><b>Mỗi topic chở đúng MỘT loại event</b> (topic-per-event-type). Nhờ vậy
 * consumer biết chắc kiểu payload mà không cần header kiểu dữ liệu, và
 * {@code EventEnvelope<T>} được deserialize thẳng ra kiểu cụ thể.
 * Cách còn lại — một topic {@code order-events} chở mọi thứ — giữ thứ tự tốt
 * hơn giữa các loại event, nhưng buộc consumer phải tự phân loại payload.
 *
 * <p><b>Ai tạo topic:</b> service PHÁT event khai báo topic của mình
 * (order-service tạo {@code order.created}). Broker đã tắt
 * {@code auto.create.topics.enable} có chủ đích — tự tạo topic che giấu lỗi
 * gõ sai tên.
 */
public final class Topics {

    private Topics() {}

    /** order-service → inventory-service: đơn mới cần giữ hàng. */
    public static final String ORDER_CREATED = "order.created";

    /** inventory-service → order-service: đã giữ đủ hàng cho cả đơn. */
    public static final String STOCK_RESERVED = "stock.reserved";

    /** inventory-service → order-service: không giữ được, đơn phải huỷ. */
    public static final String STOCK_RESERVATION_FAILED = "stock.reservation-failed";

    /** inventory-service → order-service: đã nhả hàng về kho — bước đền bù HOÀN TẤT. */
    public static final String STOCK_RELEASED = "stock.released";

    // ---- Saga: thanh toán ---------------------------------------------------

    /** order-service → payment-service: lệnh thu tiền cho đơn đã giữ được hàng. */
    public static final String PAYMENT_REQUESTED = "payment.requested";

    /** payment-service → order-service: đã thu tiền. */
    public static final String PAYMENT_COMPLETED = "payment.completed";

    /** payment-service → order-service: cổng thanh toán từ chối. */
    public static final String PAYMENT_FAILED = "payment.failed";

    // ---- Saga: kết cục của đơn ----------------------------------------------

    /** order-service → inventory (chốt hàng), notification (tuần 7). */
    public static final String ORDER_CONFIRMED = "order.confirmed";

    /** order-service → inventory (nhả hàng — ĐỀN BÙ), notification (tuần 7). */
    public static final String ORDER_CANCELLED = "order.cancelled";
}
