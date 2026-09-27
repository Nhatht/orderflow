// Hợp đồng API qua gateway (HANDOFF mục C). Giữ đúng tên trường của backend.
//
// Tiền: backend trả BigDecimal dưới dạng số JSON (vd. 45000.0000). Frontend CHỈ
// hiển thị, không tự cộng/trừ bằng number của JS — tổng lấy từ totalAmount.

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  /** Số giây token còn hiệu lực. */
  expiresIn: number
}

export type OrderStatus = 'PENDING' | 'STOCK_RESERVED' | 'PAID' | 'CONFIRMED' | 'CANCELLED'

export interface OrderItemView {
  productId: string
  productName: string
  quantity: number
  unitPrice: number
  subtotal: number
}

export interface OrderView {
  id: string
  customerId: string
  status: OrderStatus
  totalAmount: number
  currency: string
  items: OrderItemView[]
  createdAt: string
  updatedAt: string
}

export type SagaStatus =
  | 'STARTED'
  | 'AWAITING_PAYMENT'
  | 'COMPLETED'
  | 'COMPENSATING'
  | 'COMPENSATED'
  | 'FAILED'

export type SagaStep = 'RESERVE_STOCK' | 'PROCESS_PAYMENT' | 'CONFIRM_ORDER' | 'RELEASE_STOCK'
export type StepOutcome = 'REQUESTED' | 'SUCCEEDED' | 'FAILED'

// order-service bật `default-property-inclusion: non_null`: trường null bị BỎ khỏi JSON,
// nên detail / failureReason thường vắng mặt hẳn (undefined) chứ không phải null.
export interface SagaStepView {
  step: SagaStep
  outcome: StepOutcome
  detail?: string | null
  occurredAt: string
}

export interface SagaView {
  orderId: string
  status: SagaStatus
  currentStep: SagaStep | null
  failureReason?: string | null
  startedAt: string
  updatedAt: string
  steps: SagaStepView[]
}

export interface StockView {
  productId: string
  availableQty: number
  reservedQty: number
  totalQty: number
}

/** Thân lỗi chuẩn của các service. Lưu ý: gateway trả 401 KHÔNG có thân. */
export interface ErrorResponse {
  timestamp: string
  status: number
  code: string
  message: string
  errors?: { field: string; message: string }[]
}

/** GET /api/inventory/products (tuần 10). price là BigDecimal của backend, chỉ hiển thị. */
export interface ProductView {
  productId: string
  sku: string
  name: string
  price: number
  currency: string
  availableQty: number
}

/** POST /api/orders. KHÔNG có customerId: gateway gắn danh tính từ token. */
export interface PlaceOrderRequest {
  currency: string
  items: {
    productId: string
    productName: string
    quantity: number
    unitPrice: number
  }[]
}
