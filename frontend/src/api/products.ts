import { apiFetch } from './client'
import type { ProductView } from './types'

export const productsQueryKey = ['products'] as const

/** Catalog: sản phẩm + số còn bán được. Backend không cache danh sách này. */
export function listProducts(): Promise<ProductView[]> {
  return apiFetch<ProductView[]>('/api/inventory/products')
}
