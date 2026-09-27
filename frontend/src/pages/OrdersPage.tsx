import { useEffect } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { CaretRight, Receipt, WarningCircle } from '@phosphor-icons/react'
import { isFinalStatus, listMyOrders, myOrdersQueryKey } from '../api/orders'
import { productsQueryKey } from '../api/products'
import type { OrderView } from '../api/types'
import { describeApiError } from '../lib/errors'
import { formatDateTime, formatMoney, shortId } from '../lib/format'
import { OrderStatusBadge } from '../components/ui/StatusBadge'
import { Skeleton } from '../components/ui/Skeleton'
import { Button } from '../components/ui/Button'

/**
 * Poll 2 giây khi còn đơn chưa tới trạng thái cuối. Gateway giới hạn 10 request/giây
 * mỗi người (dồn 20): 0,5 request/giây là rất xa giới hạn, kể cả mở vài tab.
 * Không poll dày hơn 1 giây (HANDOFF D.5). Tab ẩn thì TanStack Query tự dừng
 * (refetchIntervalInBackground mặc định false).
 */
const POLL_MS = 2_000

export function OrdersPage() {
  const { data, isPending, isError, error, refetch, isFetching } = useQuery({
    queryKey: myOrdersQueryKey,
    queryFn: listMyOrders,
    refetchInterval: (query) =>
      query.state.data?.some((o) => !isFinalStatus(o.status)) ? POLL_MS : false,
  })

  // Trạng thái đơn đổi (giữ hàng, huỷ → nhả hàng, xác nhận) = số còn bán trong catalog
  // vừa đổi. Invalidate ở đây thay vì lúc bấm "Đặt hàng", khi saga chưa kịp giữ hàng.
  const queryClient = useQueryClient()
  const statusSignature = data?.map((o) => `${o.id}:${o.status}`).join(',')
  useEffect(() => {
    if (statusSignature !== undefined) void queryClient.invalidateQueries({ queryKey: productsQueryKey })
  }, [statusSignature, queryClient])

  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Đơn hàng</h1>
      <p className="mt-1 text-sm text-text-muted">
        Mới nhất ở trên cùng. Đơn đang xử lý tự cập nhật trạng thái. Mở một đơn để xem saga chạy từng bước.
      </p>

      <div className="mt-8">
        {/* Chỉ báo lỗi khi CHƯA có dữ liệu: poll 2 giây lỗi thoáng qua không được xoá danh sách. */}
        {isPending ? (
          <OrdersSkeleton />
        ) : isError && !data ? (
          <div className="flex flex-col items-start gap-3 rounded-xl border border-border bg-surface p-6">
            <p className="flex items-center gap-2 text-sm font-medium text-fail">
              <WarningCircle size={18} aria-hidden />
              Không tải được danh sách đơn
            </p>
            <p className="text-sm text-text-muted">{describeApiError(error, 'order-service')}</p>
            <Button variant="secondary" onClick={() => refetch()} loading={isFetching}>
              Thử lại
            </Button>
          </div>
        ) : data.length === 0 ? (
          <div className="flex flex-col items-center rounded-xl border border-dashed border-border-strong px-6 py-16 text-center">
            <Receipt size={32} className="text-text-subtle" aria-hidden />
            <p className="mt-4 font-medium">Chưa có đơn nào</p>
            <p className="mt-1 max-w-sm text-sm text-text-muted">
              Đơn bạn đặt sẽ hiện ở đây, kèm trạng thái saga của từng đơn.
            </p>
            <Link to="/products" className="mt-6 text-sm font-medium text-accent hover:underline">
              Xem sản phẩm
            </Link>
          </div>
        ) : (
          <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-surface shadow-card">
            {data.map((o) => (
              <OrderRow key={o.id} order={o} />
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

/** Cả dòng là một link tới trang chi tiết (timeline saga). */
function OrderRow({ order: o }: { order: OrderView }) {
  return (
    <li>
      <Link
        to={`/orders/${o.id}`}
        className="grid grid-cols-[1fr_auto] items-center gap-x-6 gap-y-1 px-5 py-4 transition-colors hover:bg-surface-muted focus-visible:bg-surface-muted sm:grid-cols-[7rem_1fr_auto_8rem_1rem]"
      >
        <span className="num text-sm font-medium">#{shortId(o.id)}</span>
        <span className="order-3 col-span-2 text-sm text-text-muted sm:order-none sm:col-span-1">
          {o.items.length} sản phẩm, {formatDateTime(o.createdAt)}
        </span>
        <span className="num justify-self-end text-sm font-medium">{formatMoney(o.totalAmount, o.currency)}</span>
        <span className="order-4 justify-self-start sm:order-none sm:justify-self-end">
          <OrderStatusBadge status={o.status} />
        </span>
        <CaretRight size={14} className="hidden text-text-subtle sm:block" aria-hidden />
      </Link>
    </li>
  )
}

function OrdersSkeleton() {
  return (
    <div className="divide-y divide-border rounded-xl border border-border bg-surface">
      {[0, 1, 2].map((i) => (
        <div key={i} className="flex items-center gap-6 px-5 py-4">
          <Skeleton className="h-4 w-20" />
          <Skeleton className="h-4 flex-1" />
          <Skeleton className="h-4 w-24" />
          <Skeleton className="h-6 w-24 rounded-full" />
        </div>
      ))}
    </div>
  )
}
