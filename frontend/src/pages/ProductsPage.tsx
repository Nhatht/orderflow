import { Link } from 'react-router'
import { Package, Plus, ShoppingCart, WarningCircle } from '@phosphor-icons/react'
import type { ProductView } from '../api/types'
import { useProducts } from '../hooks/useProducts'
import { selectItemCount, useCartStore } from '../stores/cartStore'
import { describeApiError } from '../lib/errors'
import { formatMoney } from '../lib/format'
import { cn } from '../lib/cn'
import { Button } from '../components/ui/Button'
import { Skeleton } from '../components/ui/Skeleton'
import { QuantityStepper } from '../components/ui/QuantityStepper'

export function ProductsPage() {
  const { query } = useProducts()
  const itemCount = useCartStore(selectItemCount)

  return (
    <div>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Sản phẩm</h1>
          <p className="mt-1 text-sm text-text-muted">Giá và số còn bán lấy trực tiếp từ inventory-service.</p>
        </div>
        {itemCount > 0 && (
          <Link
            to="/cart"
            className="inline-flex h-10 items-center gap-2 rounded-lg bg-accent px-4 text-sm font-medium text-accent-fg transition-colors hover:bg-accent-hover active:translate-y-px"
          >
            <ShoppingCart size={16} aria-hidden />
            Xem giỏ hàng
            <span className="num rounded-full bg-accent-fg/20 px-1.5 text-xs">{itemCount}</span>
          </Link>
        )}
      </div>

      <div className="mt-8">
        {query.isPending ? (
          <ProductsSkeleton />
        ) : query.isError ? (
          <div className="flex flex-col items-start gap-3 rounded-xl border border-border bg-surface p-6">
            <p className="flex items-center gap-2 text-sm font-medium text-fail">
              <WarningCircle size={18} aria-hidden />
              Không tải được danh sách sản phẩm
            </p>
            <p className="text-sm text-text-muted">{describeApiError(query.error, 'inventory-service')}</p>
            <Button variant="secondary" onClick={() => query.refetch()} loading={query.isFetching}>
              Thử lại
            </Button>
          </div>
        ) : query.data.length === 0 ? (
          <div className="flex flex-col items-center rounded-xl border border-dashed border-border-strong px-6 py-16 text-center">
            <Package size={32} className="text-text-subtle" aria-hidden />
            <p className="mt-4 font-medium">Chưa có sản phẩm nào</p>
            <p className="mt-1 max-w-sm text-sm text-text-muted">
              Dữ liệu mẫu nằm trong migration Flyway của inventory-service.
            </p>
          </div>
        ) : (
          <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-surface shadow-card">
            {query.data.map((p) => (
              <ProductRow key={p.productId} product={p} />
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function ProductRow({ product: p }: { product: ProductView }) {
  const inCart = useCartStore((s) => s.quantities[p.productId] ?? 0)
  const add = useCartStore((s) => s.add)
  const setQuantity = useCartStore((s) => s.setQuantity)
  const remove = useCartStore((s) => s.remove)
  const soldOut = p.availableQty === 0

  return (
    <li className="grid grid-cols-[1fr_auto] items-center gap-x-6 gap-y-3 px-5 py-4 sm:grid-cols-[1fr_8rem_7rem_9.5rem]">
      <div className="min-w-0">
        <p className={cn('truncate font-medium', soldOut && 'text-text-muted')}>{p.name}</p>
        <p className="num mt-0.5 text-xs text-text-subtle">{p.sku}</p>
      </div>
      <span className="num justify-self-end text-sm font-medium">{formatMoney(p.price, p.currency)}</span>
      <Availability qty={p.availableQty} />
      <div className="justify-self-end">
        {inCart > 0 ? (
          <QuantityStepper
            label={p.name}
            value={inCart}
            max={p.availableQty}
            onChange={(q) => setQuantity(p.productId, q, p.availableQty)}
            onRemove={() => remove(p.productId)}
          />
        ) : (
          <Button
            variant="secondary"
            className="h-9 px-3"
            disabled={soldOut}
            onClick={() => add(p.productId, p.availableQty)}
          >
            <Plus size={14} aria-hidden />
            Thêm vào giỏ
          </Button>
        )}
      </div>
    </li>
  )
}

function Availability({ qty }: { qty: number }) {
  const text = qty === 0 ? 'Hết hàng' : qty <= 5 ? `Chỉ còn ${qty}` : `Còn ${qty}`
  return (
    <span
      className={cn(
        'text-sm sm:justify-self-end',
        qty === 0 ? 'text-text-subtle' : qty <= 5 ? 'font-medium text-text' : 'text-text-muted',
      )}
    >
      {text}
    </span>
  )
}

function ProductsSkeleton() {
  return (
    <div className="divide-y divide-border rounded-xl border border-border bg-surface">
      {[0, 1, 2, 3].map((i) => (
        <div key={i} className="flex items-center gap-6 px-5 py-4">
          <div className="grid flex-1 gap-1.5">
            <Skeleton className="h-4 w-48" />
            <Skeleton className="h-3 w-16" />
          </div>
          <Skeleton className="h-4 w-24" />
          <Skeleton className="hidden h-4 w-16 sm:block" />
          <Skeleton className="h-9 w-32 rounded-lg" />
        </div>
      ))}
    </div>
  )
}
