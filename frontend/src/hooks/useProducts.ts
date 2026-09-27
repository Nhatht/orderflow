import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { listProducts, productsQueryKey } from '../api/products'
import type { ProductView } from '../api/types'

/**
 * Catalog dùng chung cho trang Sản phẩm và Giỏ hàng: cùng query key nên chỉ một
 * request, hai trang cùng thấy một số liệu (TanStack Query dedupe + cache).
 */
export function useProducts() {
  const query = useQuery({
    queryKey: productsQueryKey,
    queryFn: listProducts,
    // Tồn kho đổi theo đơn của người khác: coi là cũ sau 5 giây, tải lại khi quay về tab.
    staleTime: 5_000,
  })
  const byId = useMemo(() => {
    const map = new Map<string, ProductView>()
    for (const p of query.data ?? []) map.set(p.productId, p)
    return map
  }, [query.data])
  // Trả query nguyên vẹn (không spread): TanStack Query theo dõi thuộc tính nào được
  // đọc để chỉ render lại khi cần; spread sẽ "đọc" hết và mất tối ưu đó.
  return { query, byId }
}
