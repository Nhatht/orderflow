import { useEffect } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation, useParams } from 'react-router'
import { ArrowLeft, Info, MagnifyingGlass, WarningCircle } from '@phosphor-icons/react'
import { ApiError } from '../api/client'
import { getOrder, getSaga, isFinalSagaStatus, myOrdersQueryKey, orderQueryKey, sagaQueryKey } from '../api/orders'
import { productsQueryKey } from '../api/products'
import type { OrderView, SagaStatus } from '../api/types'
import { describeApiError } from '../lib/errors'
import { formatDateTime, formatMoney, shortId } from '../lib/format'
import { Button } from '../components/ui/Button'
import { Skeleton } from '../components/ui/Skeleton'
import { OrderStatusBadge } from '../components/ui/StatusBadge'
import { SagaTimeline } from '../components/saga/SagaTimeline'

/**
 * Poll saga mỗi giây tới khi saga ở trạng thái cuối (HANDOFF D.4). 1 request/giây so với
 * giới hạn 10 request/giây (dồn 20) của gateway: còn dư cho vài tab. Không dày hơn 1 giây
 * (D.5). Tab ẩn thì TanStack Query tự dừng (refetchIntervalInBackground mặc định false),
 * quay lại tab thì refetchOnWindowFocus lấy ngay bản mới.
 */
const SAGA_POLL_MS = 1_000

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function OrderDetailPage() {
  const { orderId = '' } = useParams()
  // order-service trả 400 INVALID_PARAMETER cho id không phải UUID. Chặn ngay ở đây cho
  // khỏi tốn request: với người dùng, "không phải UUID" và "không tồn tại" là một.
  const validId = UUID_RE.test(orderId)
  const location = useLocation()
  const justPlaced = (location.state as { justPlaced?: boolean } | null)?.justPlaced === true

  const orderQuery = useQuery({
    queryKey: orderQueryKey(orderId),
    queryFn: () => getOrder(orderId),
    enabled: validId,
  })

  const sagaQuery = useQuery({
    queryKey: sagaQueryKey(orderId),
    queryFn: () => getSaga(orderId),
    enabled: validId,
    // Saga luôn "cũ" ngay khi về tới: nó đang chạy ở chỗ khác.
    staleTime: 0,
    refetchInterval: (query) => {
      if (isNotFound(query.state.error)) return false
      const saga = query.state.data
      return saga && isFinalSagaStatus(saga.status) ? false : SAGA_POLL_MS
    },
  })

  useKeepCachesInSync(orderId, orderQuery.data, sagaQuery.data?.status, sagaQuery.data?.steps.length)

  if (!validId || isNotFound(orderQuery.error) || isNotFound(sagaQuery.error)) {
    return <OrderNotFound />
  }

  const order = orderQuery.data
  const saga = sagaQuery.data

  return (
    <div>
      <Link
        to="/orders"
        className="inline-flex items-center gap-1.5 text-sm text-text-muted transition-colors hover:text-text"
      >
        <ArrowLeft size={14} aria-hidden />
        Đơn hàng
      </Link>

      {/* Có dữ liệu đơn thì luôn hiện nó: lần tải lại (do saga đổi) lỗi thoáng qua không được
          xoá cả trang, kể cả timeline đang chạy. LoadError chỉ khi chưa từng tải được đơn. */}
      {orderQuery.isPending ? (
        <DetailSkeleton />
      ) : !order ? (
        <LoadError
          title="Không tải được đơn"
          error={orderQuery.error ?? new Error('Không tải được đơn')}
          onRetry={() => void orderQuery.refetch()}
          retrying={orderQuery.isFetching}
        />
      ) : (
        <>
          <OrderHeader order={order} />
          {justPlaced && <JustPlacedNotice />}
          <div className="mt-8 grid items-start gap-6 lg:grid-cols-[1fr_20rem]">
            {saga ? (
              <SagaTimeline saga={saga} stale={sagaQuery.isError} />
            ) : sagaQuery.isError ? (
              <LoadError
                title="Không tải được saga của đơn"
                error={sagaQuery.error}
                onRetry={() => void sagaQuery.refetch()}
                retrying={sagaQuery.isFetching}
              />
            ) : (
              <TimelineSkeleton />
            )}
            <OrderSummary order={order} />
          </div>
        </>
      )}
    </div>
  )
}

function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}

/**
 * Saga là thứ được poll; đơn, danh sách đơn và catalog chạy theo nó:
 * - Saga đổi (trạng thái hoặc thêm bước) → tải lại đơn. Orchestrator ghi trạng thái đơn
 *   và saga trong CÙNG một transaction, nên saga đổi là đơn cũng vừa đổi. Không cần poll
 *   thêm một endpoint thứ hai (tiết kiệm một nửa số request so với poll cả hai).
 * - Đơn mới về → vá thẳng vào cache danh sách (setQueryData), quay về /orders thấy ngay
 *   trạng thái mới mà không cần chờ refetch.
 * - Saga tới trạng thái cuối → đánh dấu catalog cũ: tồn kho đã đổi (chốt hàng hoặc nhả
 *   hàng). Phải chờ trạng thái cuối của SAGA, không phải của đơn: đơn CANCELLED ngay khi
 *   thẻ bị từ chối, nhưng hàng chỉ về kho khi saga nhận stock.released (COMPENSATED).
 */
function useKeepCachesInSync(
  orderId: string,
  order: OrderView | undefined,
  sagaStatus: SagaStatus | undefined,
  stepCount: number | undefined,
) {
  const queryClient = useQueryClient()

  const sagaSignature = sagaStatus === undefined ? undefined : `${sagaStatus}:${stepCount}`
  const sagaFinal = sagaStatus !== undefined && isFinalSagaStatus(sagaStatus)
  useEffect(() => {
    if (sagaSignature === undefined) return
    void queryClient.invalidateQueries({ queryKey: orderQueryKey(orderId), exact: true })
    if (sagaFinal) {
      void queryClient.invalidateQueries({ queryKey: productsQueryKey })
      void queryClient.invalidateQueries({ queryKey: myOrdersQueryKey })
    }
  }, [sagaSignature, sagaFinal, orderId, queryClient])

  useEffect(() => {
    if (!order) return
    queryClient.setQueryData<OrderView[]>(myOrdersQueryKey, (prev) =>
      prev?.map((o) => (o.id === order.id ? order : o)),
    )
  }, [order, queryClient])
}

function OrderHeader({ order }: { order: OrderView }) {
  return (
    <div className="mt-4 flex flex-wrap items-end justify-between gap-4">
      <div>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold tracking-tight">
            Đơn <span className="num">#{shortId(order.id)}</span>
          </h1>
          <OrderStatusBadge status={order.status} />
        </div>
        <p className="mt-1 text-sm text-text-muted">
          Đặt lúc <span className="num">{formatDateTime(order.createdAt)}</span>
        </p>
      </div>
      <p className="text-right">
        <span className="block text-xs text-text-muted">Tổng tiền</span>
        <span className="num text-xl font-semibold">{formatMoney(order.totalAmount, order.currency)}</span>
      </p>
    </div>
  )
}

/** Sau khi bấm "Đặt hàng": nói rõ 201 chỉ là "đã ghi nhận", phần còn lại chạy bất đồng bộ. */
function JustPlacedNotice() {
  return (
    <div role="status" className="mt-6 flex items-start gap-3 rounded-xl border border-border bg-surface px-4 py-3 text-sm">
      <Info size={18} className="mt-px shrink-0 text-accent" aria-hidden />
      <p className="text-text-muted">
        <span className="font-medium text-text">Đơn đã được ghi nhận.</span> Giữ hàng, thu tiền và xác nhận diễn
        ra bất đồng bộ qua Kafka; timeline bên dưới tự cập nhật từng bước.
      </p>
    </div>
  )
}

function OrderSummary({ order }: { order: OrderView }) {
  return (
    <aside className="rounded-xl border border-border bg-surface p-5 shadow-card">
      <h2 className="font-semibold">Sản phẩm</h2>
      <ul className="mt-3 divide-y divide-border">
        {order.items.map((item) => (
          <li key={item.productId} className="flex items-start justify-between gap-4 py-2.5">
            <div className="min-w-0">
              <p className="text-sm font-medium">{item.productName}</p>
              <p className="num mt-0.5 text-xs text-text-subtle">
                {item.quantity} × {formatMoney(item.unitPrice, order.currency)}
              </p>
            </div>
            <span className="num shrink-0 text-sm">{formatMoney(item.subtotal, order.currency)}</span>
          </li>
        ))}
      </ul>
      <div className="mt-1 flex items-baseline justify-between gap-4 border-t border-border pt-3">
        <span className="text-sm font-medium">Tổng</span>
        <span className="num font-semibold">{formatMoney(order.totalAmount, order.currency)}</span>
      </div>
      <p className="mt-1.5 text-xs text-text-subtle">Do order-service tính bằng BigDecimal.</p>

      <dl className="mt-5 grid gap-2 border-t border-border pt-4 text-xs">
        <div>
          <dt className="text-text-muted">Mã đơn</dt>
          <dd className="num mt-0.5 break-all text-text">{order.id}</dd>
        </div>
        <div className="flex justify-between gap-4">
          <dt className="text-text-muted">Tạo lúc</dt>
          <dd className="num text-text">{formatDateTime(order.createdAt)}</dd>
        </div>
        <div className="flex justify-between gap-4">
          <dt className="text-text-muted">Cập nhật lúc</dt>
          <dd className="num text-text">{formatDateTime(order.updatedAt)}</dd>
        </div>
      </dl>
    </aside>
  )
}

/**
 * Đơn không tồn tại HOẶC của người khác: backend trả 404 cho cả hai (không phải 403,
 * vì 403 xác nhận "đơn này có tồn tại", HANDOFF C). Câu chữ ở đây giữ đúng sự mơ hồ đó.
 */
function OrderNotFound() {
  return (
    <div className="grid min-h-[50dvh] place-items-center text-center">
      <div className="flex max-w-sm flex-col items-center">
        <MagnifyingGlass size={32} className="text-text-subtle" aria-hidden />
        <h1 className="mt-4 text-xl font-semibold tracking-tight">Không tìm thấy đơn</h1>
        <p className="mt-2 text-sm text-text-muted">
          Đơn này không tồn tại hoặc không thuộc tài khoản đang đăng nhập.
        </p>
        <Link to="/orders" className="mt-6 text-sm font-medium text-accent hover:underline">
          Về danh sách đơn
        </Link>
      </div>
    </div>
  )
}

function LoadError({
  title,
  error,
  onRetry,
  retrying,
}: {
  title: string
  error: Error
  onRetry: () => void
  retrying: boolean
}) {
  return (
    <div className="mt-6 flex flex-col items-start gap-3 rounded-xl border border-border bg-surface p-6">
      <p className="flex items-center gap-2 text-sm font-medium text-fail">
        <WarningCircle size={18} aria-hidden />
        {title}
      </p>
      <p className="text-sm text-text-muted">{describeApiError(error, 'order-service')}</p>
      <Button variant="secondary" onClick={onRetry} loading={retrying}>
        Thử lại
      </Button>
    </div>
  )
}

function DetailSkeleton() {
  return (
    <>
      <div className="mt-4 flex items-end justify-between gap-4">
        <div className="grid gap-2">
          <Skeleton className="h-8 w-56" />
          <Skeleton className="h-4 w-40" />
        </div>
        <Skeleton className="h-7 w-28" />
      </div>
      <div className="mt-8 grid items-start gap-6 lg:grid-cols-[1fr_20rem]">
        <TimelineSkeleton />
        <div className="grid gap-3 rounded-xl border border-border bg-surface p-5">
          <Skeleton className="h-4 w-24" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-2/3" />
        </div>
      </div>
    </>
  )
}

function TimelineSkeleton() {
  return (
    <div className="rounded-xl border border-border bg-surface">
      <div className="grid gap-2 border-b border-border px-6 py-4">
        <Skeleton className="h-5 w-40" />
        <Skeleton className="h-4 w-3/4" />
      </div>
      <div className="grid gap-6 px-6 py-5">
        {[0, 1, 2].map((i) => (
          <div key={i} className="grid grid-cols-[4.5rem_1.5rem_1fr] gap-x-3">
            <Skeleton className="h-3 w-12 justify-self-end" />
            <Skeleton className="size-[22px] rounded-full" />
            <div className="grid gap-1.5">
              <Skeleton className="h-4 w-32" />
              <Skeleton className="h-3 w-48" />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
