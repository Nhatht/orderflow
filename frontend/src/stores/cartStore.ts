import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import { useAuthStore } from './authStore'

/*
 * Giỏ hàng = CLIENT STATE: chỉ lưu Ý ĐỊNH của người dùng (mua sản phẩm nào, bao nhiêu).
 *
 * Giá, tên, số còn bán là SERVER STATE: lấy từ query ['products'] (TanStack Query) mỗi
 * lần hiển thị, KHÔNG chụp vào giỏ. Nếu chụp giá lúc bấm "Thêm" rồi lưu vào storage,
 * giỏ sẽ giữ giá cũ tới khi xoá giỏ, và đơn gửi đi mang giá cũ (order-service tin giá
 * client gửi, HANDOFF D.6). Tương tự .NET: đừng cache entity trong session, chỉ cache id.
 *
 * Lưu ở sessionStorage, CÙNG chỗ với token (authStore): giỏ sống đúng bằng phiên đăng
 * nhập của tab đó. localStorage thì dùng chung mọi tab, alice ở tab 1 và bob ở tab 2
 * sẽ thấy chung một giỏ.
 */

interface CartState {
  /** productId → số lượng (luôn >= 1; về 0 thì xoá khỏi map). */
  quantities: Record<string, number>
  /** +1, không vượt `max` (availableQty lúc bấm). */
  add: (productId: string, max: number) => void
  /** Đặt số lượng, kẹp trong [1, max]. Muốn xoá thì gọi remove. */
  setQuantity: (productId: string, quantity: number, max: number) => void
  remove: (productId: string) => void
  clear: () => void
}

export const useCartStore = create<CartState>()(
  persist(
    (set) => ({
      quantities: {},
      add: (productId, max) =>
        set((s) => {
          const next = Math.min((s.quantities[productId] ?? 0) + 1, max)
          if (next < 1) return s
          return { quantities: { ...s.quantities, [productId]: next } }
        }),
      setQuantity: (productId, quantity, max) =>
        set((s) => {
          const next = Math.max(1, Math.min(Math.trunc(quantity), max))
          if (max < 1 || Number.isNaN(next)) return s
          return { quantities: { ...s.quantities, [productId]: next } }
        }),
      remove: (productId) =>
        set((s) => {
          const { [productId]: _removed, ...rest } = s.quantities
          return { quantities: rest }
        }),
      clear: () => set({ quantities: {} }),
    }),
    {
      name: 'orderflow-cart',
      storage: createJSONStorage(() => sessionStorage),
      partialize: (s) => ({ quantities: s.quantities }),
    },
  ),
)

/** Tổng số món (để hiện trên nav). */
export function selectItemCount(s: CartState): number {
  return Object.values(s.quantities).reduce((acc, q) => acc + q, 0)
}

/*
 * Giỏ thuộc về MỘT người dùng: đổi người (đăng xuất, 401, hoặc đăng nhập người khác đè
 * lên phiên cũ) là xoá giỏ. Xét username chứ không chỉ "token về null": vào thẳng /login
 * khi token đã hết hạn thì RequireAuth không chạy, logout() không được gọi, token cũ còn
 * nguyên, và bob đăng nhập đè lên sẽ thấy giỏ của alice. Cùng người đăng nhập lại thì giữ giỏ.
 * Nghe thay đổi của authStore thay vì sửa authStore, để hai store không phụ thuộc vòng.
 */
useAuthStore.subscribe((state, prev) => {
  if (state.username !== prev.username) {
    useCartStore.getState().clear()
  }
})
