import { apiFetch } from './client'
import type { OrderStatus, OrderView, PlaceOrderRequest, SagaStatus, SagaView } from './types'

export const myOrdersQueryKey = ['orders', 'mine'] as const

/** Key của một đơn và saga của nó. Cùng tiền tố 'orders' để invalidate cả nhóm khi cần. */
export const orderQueryKey = (orderId: string) => ['orders', 'detail', orderId] as const
export const sagaQueryKey = (orderId: string) => ['orders', 'detail', orderId, 'saga'] as const

/** Đơn của chính người đang đăng nhập — gateway tự gắn danh tính từ token. */
export function listMyOrders(): Promise<OrderView[]> {
  return apiFetch<OrderView[]>('/api/orders')
}

/** Một đơn. Đơn của người khác → 404 (không phải 403: không xác nhận đơn đó tồn tại). */
export function getOrder(orderId: string): Promise<OrderView> {
  return apiFetch<OrderView>(`/api/orders/${encodeURIComponent(orderId)}`)
}

/** Trạng thái saga + nhật ký các bước (append-only). Nguồn của trang timeline. */
export function getSaga(orderId: string): Promise<SagaView> {
  return apiFetch<SagaView>(`/api/orders/${encodeURIComponent(orderId)}/saga`)
}

/**
 * Tạo đơn → 201 + OrderView (status PENDING). Saga chạy BẤT ĐỒNG BỘ sau đó:
 * response này chỉ nói "đơn đã được ghi nhận", chưa nói giữ hàng hay thu tiền được.
 */
export function placeOrder(req: PlaceOrderRequest): Promise<OrderView> {
  return apiFetch<OrderView>('/api/orders', { method: 'POST', body: JSON.stringify(req) })
}

/**
 * Trạng thái cuối của ĐƠN. Lưu ý: đơn CANCELLED sớm hơn saga một nhịp. Khi thanh toán
 * bị từ chối, đơn chuyển CANCELLED cùng lúc saga sang COMPENSATING, rồi saga mới chờ
 * inventory nhả hàng. Muốn biết "mọi thứ đã xong chưa" thì hỏi isFinalSagaStatus.
 */
export function isFinalStatus(status: OrderStatus): boolean {
  return status === 'CONFIRMED' || status === 'CANCELLED'
}

/**
 * Trạng thái cuối của SAGA, chép đúng SagaStatus.isFinal() ở order-service:
 * COMPLETED, COMPENSATED, FAILED. COMPENSATING chưa phải cuối (còn chờ stock.released).
 */
export function isFinalSagaStatus(status: SagaStatus): boolean {
  return status === 'COMPLETED' || status === 'COMPENSATED' || status === 'FAILED'
}
