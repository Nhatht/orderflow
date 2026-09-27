import { Link, useNavigate } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowUDownLeft, ShoppingCart, Trash, WarningCircle } from '@phosphor-icons/react'
import { ApiError } from '../api/client'
import { myOrdersQueryKey, orderQueryKey, placeOrder } from '../api/orders'
import { productsQueryKey } from '../api/products'
import type { OrderView, PlaceOrderRequest, ProductView } from '../api/types'
import { useProducts } from '../hooks/useProducts'
import { useCartStore } from '../stores/cartStore'
import { describeApiError } from '../lib/errors'
import { formatMinor, formatMoney } from '../lib/format'
import { isDemoDeclinedTotal, lineTotal, sum, toMinor, type Minor } from '../lib/money'
import { Button } from '../components/ui/Button'
import { Skeleton } from '../components/ui/Skeleton'
import { QuantityStepper } from '../components/ui/QuantityStepper'

/** Dòng giỏ = ý định trong store (id, số lượng) ghép với dữ liệu sống của catalog. */
interface CartLine {
  productId: string
  quantity: number
  product: ProductView | undefined
  subtotal: Minor
  /** Người khác vừa mua mất: số trong giỏ > số còn bán. */
  exceedsStock: boolean
}

export function CartPage() {
  const quantities = useCartStore((s) => s.quantities)
  const { query, byId } = useProducts()

  const lines: CartLine[] = Object.entries(quantities).map(([productId, quantity]) => {
    const product = byId.get(productId)
    return {
      productId,
      quantity,
      product,
      subtotal: product ? lineTotal(toMinor(product.price), quantity) : 0n,
      exceedsStock: product ? quantity > product.availableQty : true,
    }
  })

  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Giỏ hàng</h1>
      <p className="mt-1 text-sm text-text-muted">Kiểm tra lại rồi đặt hàng. Saga chạy ngay sau khi đơn được tạo.</p>

      <div className="mt-8">
        {lines.length === 0 ? (
          <EmptyCart />
        ) : query.isPending ? (
          <CartSkeleton />
        ) : query.isError && !query.data ? (
          <div className="flex flex-col items-start gap-3 rounded-xl border border-border bg-surface p-6">
            <p className="flex items-center gap-2 text-sm font-medium text-fail">
              <WarningCircle size={18} aria-hidden />
              Không tải được giá và tồn kho hiện tại
            </p>
            <p className="text-sm text-text-muted">{describeApiError(query.error, 'inventory-service')}</p>
            <Button variant="secondary" onClick={() => query.refetch()} loading={query.isFetching}>
              Thử lại
            </Button>
          </div>
        ) : (
          <div className="grid items-start gap-6 lg:grid-cols-[1fr_22rem]">
            <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-surface shadow-card">
              {lines.map((line) => (
                <CartLineRow key={line.productId} line={line} />
              ))}
            </ul>
            <CheckoutPanel lines={lines} catalog={query.data} />
          </div>
        )}
      </div>
    </div>
  )
}

function CartLineRow({ line }: { line: CartLine }) {
  const setQuantity = useCartStore((s) => s.setQuantity)
  const remove = useCartStore((s) => s.remove)
  const p = line.product

  if (!p) {
    return (
      <li className="flex items-center justify-between gap-4 px-5 py-4">
        <p className="text-sm text-text-muted">Sản phẩm này không còn trong catalog.</p>
        <Button variant="ghost" className="h-9 px-3" onClick={() => remove(line.productId)}>
          Bỏ khỏi giỏ
        </Button>
      </li>
    )
  }

  return (
    <li className="grid grid-cols-[1fr_auto] items-center gap-x-6 gap-y-3 px-5 py-4 sm:grid-cols-[1fr_auto_8rem_auto]">
      <div className="min-w-0">
        <p className="truncate font-medium">{p.name}</p>
        <p className="num mt-0.5 text-xs text-text-subtle">
          {formatMoney(p.price, p.currency)} / món
        </p>
        {line.exceedsStock && (
          <p className="mt-1.5 flex flex-wrap items-center gap-x-2 text-sm text-fail">
            {p.availableQty === 0 ? 'Vừa hết hàng.' : `Chỉ còn ${p.availableQty}.`}
            {p.availableQty > 0 && (
              <button
                type="button"
                onClick={() => setQuantity(p.productId, p.availableQty, p.availableQty)}
                className="font-medium text-accent hover:underline"
              >
                Giảm còn {p.availableQty}
              </button>
            )}
          </p>
        )}
      </div>
      <QuantityStepper
        label={p.name}
        value={line.quantity}
        max={p.availableQty}
        onChange={(q) => setQuantity(p.productId, q, p.availableQty)}
        onRemove={() => remove(p.productId)}
      />
      <span className="num justify-self-end text-sm font-medium">{formatMinor(line.subtotal, p.currency)}</span>
      <button
        type="button"
        onClick={() => remove(p.productId)}
        aria-label={`Xoá ${p.name} khỏi giỏ`}
        className="grid size-9 place-items-center justify-self-end rounded-lg text-text-subtle transition-colors hover:bg-surface-muted hover:text-text"
      >
        <Trash size={16} aria-hidden />
      </button>
    </li>
  )
}

function CheckoutPanel({ lines, catalog }: { lines: CartLine[]; catalog: ProductView[] }) {
  const clear = useCartStore((s) => s.clear)
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  const valid = lines.filter((l): l is CartLine & { product: ProductView } => l.product !== undefined)
  const currencies = new Set(valid.map((l) => l.product.currency))
  const currency = valid[0]?.product.currency ?? 'VND'
  const total = sum(valid.map((l) => l.subtotal))
  const itemCount = valid.reduce((acc, l) => acc + l.quantity, 0)
  const blocked = lines.some((l) => l.exceedsStock) || currencies.size > 1
  const declined = isDemoDeclinedTotal(total)
  // Sản phẩm mà riêng giá của nó đã tận cùng 99 (seed V5 của inventory), để gợi ý.
  const demoProduct = catalog.find((p) => p.availableQty > 0 && isDemoDeclinedTotal(toMinor(p.price)))

  const mutation = useMutation({
    mutationFn: (req: PlaceOrderRequest) => placeOrder(req),
    onSuccess: (order) => {
      clear()
      // Hiện đơn mới ngay (không chờ refetch), rồi vẫn invalidate để lấy bản chuẩn từ server.
      queryClient.setQueryData<OrderView[]>(myOrdersQueryKey, (prev) =>
        prev ? [order, ...prev.filter((o) => o.id !== order.id)] : prev,
      )
      void queryClient.invalidateQueries({ queryKey: myOrdersQueryKey })
      // Saga sắp giữ hàng (bất đồng bộ, qua Kafka): refetch NGAY bây giờ gần như chắc chắn
      // lấy về số CŨ rồi coi là "tươi" 5 giây. Chỉ đánh dấu cũ; trang chi tiết đơn invalidate
      // lại khi saga tới trạng thái cuối, tức là lúc số còn bán thật sự đã đổi.
      void queryClient.invalidateQueries({ queryKey: productsQueryKey, refetchType: 'none' })
      // Trang chi tiết có ngay dữ liệu đơn (không nháy skeleton), timeline tự tải saga.
      queryClient.setQueryData(orderQueryKey(order.id), order)
      // Đi thẳng tới trang chi tiết: khoảnh khắc đáng xem nhất là vài giây ngay sau khi
      // đặt, lúc saga đang chạy. Về /orders thì người xem chỉ thấy nhãn trạng thái đổi.
      navigate(`/orders/${order.id}`, { state: { justPlaced: true } })
    },
  })

  function submit() {
    mutation.mutate({
      currency,
      items: valid.map((l) => ({
        productId: l.productId,
        productName: l.product.name,
        quantity: l.quantity,
        // Gửi nguyên number backend đã trả (≤ 4 chữ số thập phân), không qua phép tính nào.
        unitPrice: l.product.price,
      })),
    })
  }

  return (
    <aside className="rounded-xl border border-border bg-surface p-5 shadow-card lg:sticky lg:top-20">
      <h2 className="font-semibold">Tóm tắt</h2>
      <dl className="mt-4 grid gap-2 text-sm">
        <div className="flex justify-between gap-4">
          <dt className="text-text-muted">Số món</dt>
          <dd className="num">{itemCount}</dd>
        </div>
        <div className="flex items-baseline justify-between gap-4 border-t border-border pt-3">
          <dt className="font-medium">Tạm tính</dt>
          <dd className="num text-lg font-semibold">{formatMinor(total, currency)}</dd>
        </div>
      </dl>
      <p className="mt-1.5 text-xs text-text-subtle">Tổng chính thức do order-service tính khi tạo đơn.</p>

      {mutation.isError && <CheckoutError error={mutation.error} lines={valid} />}
      {currencies.size > 1 && (
        <p role="alert" className="mt-4 rounded-lg bg-fail-soft px-3 py-2.5 text-sm text-fail">
          Giỏ có nhiều loại tiền tệ. Một đơn chỉ được một loại.
        </p>
      )}

      <Button
        className="mt-5 w-full"
        onClick={submit}
        loading={mutation.isPending}
        disabled={blocked || valid.length === 0}
      >
        Đặt hàng
      </Button>

      <DemoHint declined={declined} demoProduct={demoProduct} />
    </aside>
  )
}

/**
 * Gợi ý cho người xem demo: payment-service giả lập TỪ CHỐI tổng có phần nguyên tận cùng 99.
 * Màu hổ phách (compensate) chỉ ở icon, đúng nghĩa của nó: điều sắp xảy ra là đền bù.
 */
function DemoHint({ declined, demoProduct }: { declined: boolean; demoProduct: ProductView | undefined }) {
  return (
    <div className="mt-5 flex gap-2.5 border-t border-border pt-4 text-xs leading-relaxed text-text-muted">
      <ArrowUDownLeft size={16} className="mt-px shrink-0 text-compensate" aria-hidden />
      {declined ? (
        <p>
          <span className="font-medium text-text">Tổng tận cùng 99: thẻ sẽ bị từ chối.</span> Saga huỷ đơn và nhả
          lại hàng đã giữ. Trang đơn sẽ cho thấy bước đền bù.
        </p>
      ) : (
        <p>
          Muốn xem saga đền bù? Cổng thanh toán giả lập từ chối tổng có phần nguyên tận cùng 99.
          {demoProduct && ` Thử thêm ${demoProduct.name} (${formatMoney(demoProduct.price, demoProduct.currency)}).`}
        </p>
      )}
    </div>
  )
}

function CheckoutError({ error, lines }: { error: Error; lines: { product: ProductView }[] }) {
  const fieldErrors = error instanceof ApiError && error.status === 400 ? error.fieldErrors : []
  return (
    <div role="alert" className="mt-4 rounded-lg bg-fail-soft px-3 py-2.5 text-sm text-fail">
      <p className="font-medium">{describeCheckoutError(error)}</p>
      {fieldErrors.length > 0 && (
        <ul className="mt-1.5 grid gap-0.5">
          {fieldErrors.map((fe) => (
            <li key={`${fe.field}-${fe.message}`}>
              <span className="num text-xs">{fieldLabel(fe.field, lines)}</span>: {fe.message}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function describeCheckoutError(error: Error): string {
  if (error instanceof ApiError && error.status === 400) return 'Đơn không hợp lệ, máy chủ từ chối:'
  return describeApiError(error, 'order-service')
}

/** "items[1].quantity" → "Bánh tráng Tây Ninh: quantity" để người dùng biết dòng nào sai. */
function fieldLabel(field: string, lines: { product: ProductView }[]): string {
  const m = /^items\[(\d+)\]\.(.+)$/.exec(field)
  const line = m ? lines[Number(m[1])] : undefined
  return line && m ? `${line.product.name} (${m[2]})` : field
}

function EmptyCart() {
  return (
    <div className="flex flex-col items-center rounded-xl border border-dashed border-border-strong px-6 py-16 text-center">
      <ShoppingCart size={32} className="text-text-subtle" aria-hidden />
      <p className="mt-4 font-medium">Giỏ hàng trống</p>
      <p className="mt-1 max-w-sm text-sm text-text-muted">Chọn sản phẩm trong catalog, giỏ sẽ hiện ở đây.</p>
      <Link to="/products" className="mt-6 text-sm font-medium text-accent hover:underline">
        Xem sản phẩm
      </Link>
    </div>
  )
}

function CartSkeleton() {
  return (
    <div className="grid items-start gap-6 lg:grid-cols-[1fr_22rem]">
      <div className="divide-y divide-border rounded-xl border border-border bg-surface">
        {[0, 1].map((i) => (
          <div key={i} className="flex items-center gap-6 px-5 py-4">
            <div className="grid flex-1 gap-1.5">
              <Skeleton className="h-4 w-48" />
              <Skeleton className="h-3 w-24" />
            </div>
            <Skeleton className="h-9 w-28 rounded-lg" />
            <Skeleton className="h-4 w-20" />
          </div>
        ))}
      </div>
      <div className="grid gap-3 rounded-xl border border-border bg-surface p-5">
        <Skeleton className="h-4 w-20" />
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-10 w-full rounded-lg" />
      </div>
    </div>
  )
}
