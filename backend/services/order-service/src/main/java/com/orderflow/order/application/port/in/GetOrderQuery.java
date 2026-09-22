package com.orderflow.order.application.port.in;

import com.orderflow.order.application.dto.OrderView;

import java.util.List;
import java.util.UUID;

/**
 * CỔNG VÀO cho thao tác đọc.
 *
 * <p>Tách query khỏi command (tinh thần CQRS ở mức nhẹ): đọc và ghi có đặc
 * điểm rất khác nhau — đọc cần phân trang và tối ưu truy vấn, ghi cần validate
 * và transaction. Gom chung vào một interface thì cả hai đều bị gò bó.
 */
public interface GetOrderQuery {

    OrderView getById(UUID orderId);

    List<OrderView> getByCustomer(UUID customerId);
}
