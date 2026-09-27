import type { OrderStatus, SagaStatus } from '../../api/types'
import { cn } from '../../lib/cn'
import { sagaStatusCopy, toneClasses } from '../../lib/saga'

// Màu theo NGỮ NGHĨA: đang chạy = trung tính, xong = ok, huỷ = fail.
const orderStatus: Record<OrderStatus, { label: string; tone: string }> = {
  PENDING: { label: 'Đang xử lý', tone: 'bg-surface-muted text-text-muted' },
  STOCK_RESERVED: { label: 'Đã giữ hàng', tone: 'bg-accent-soft text-accent' },
  PAID: { label: 'Đã thanh toán', tone: 'bg-accent-soft text-accent' },
  CONFIRMED: { label: 'Đã xác nhận', tone: 'bg-ok-soft text-ok' },
  CANCELLED: { label: 'Đã huỷ', tone: 'bg-fail-soft text-fail' },
}

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  const s = orderStatus[status]
  return (
    <span className={cn('inline-flex h-6 items-center rounded-full px-2.5 text-xs font-medium', s.tone)}>
      {s.label}
    </span>
  )
}

export function SagaStatusBadge({ status }: { status: SagaStatus }) {
  const s = sagaStatusCopy[status]
  return (
    <span className={cn('inline-flex h-6 items-center rounded-full px-2.5 text-xs font-medium', toneClasses[s.tone])}>
      {s.label}
    </span>
  )
}
