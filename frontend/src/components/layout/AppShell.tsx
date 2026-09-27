import { NavLink, Outlet } from 'react-router'
import { SignOut } from '@phosphor-icons/react'
import { useAuthStore } from '../../stores/authStore'
import { selectItemCount, useCartStore } from '../../stores/cartStore'
import { cn } from '../../lib/cn'
import { Wordmark } from './Wordmark'

const nav = [
  { to: '/products', label: 'Sản phẩm' },
  { to: '/cart', label: 'Giỏ hàng' },
  { to: '/orders', label: 'Đơn hàng' },
]

export function AppShell() {
  const username = useAuthStore((s) => s.username)
  // Đăng xuất chỉ cần gỡ phiên: giỏ (cartStore) và cache query (main.tsx) tự xoá khi
  // phiên đổi — một chỗ cho mọi đường (nút này, 401, token hết hạn).
  const logout = useAuthStore((s) => s.logout)
  const cartCount = useCartStore(selectItemCount)

  return (
    <div className="min-h-[100dvh]">
      <header className="sticky top-0 z-10 border-b border-border bg-bg/85 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-4 px-4 sm:gap-8 sm:px-6">
          <span className="hidden sm:inline-flex">
            <Wordmark />
          </span>
          <nav className="flex items-center gap-1">
            {nav.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) =>
                  cn(
                    'inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm whitespace-nowrap transition-colors',
                    isActive ? 'bg-surface-muted font-medium text-text' : 'text-text-muted hover:text-text',
                  )
                }
              >
                {item.label}
                {item.to === '/cart' && cartCount > 0 && (
                  <span className="num rounded-full bg-accent-soft px-1.5 text-xs font-medium text-accent">
                    {cartCount}
                    <span className="sr-only"> món</span>
                  </span>
                )}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-3">
            <span className="hidden text-sm text-text-muted md:inline">
              <span className="text-text">{username}</span>
            </span>
            <button
              type="button"
              onClick={logout}
              aria-label="Đăng xuất"
              className="inline-flex h-9 items-center gap-1.5 rounded-lg px-2.5 text-sm text-text-muted transition-colors hover:bg-surface-muted hover:text-text"
            >
              <SignOut size={16} weight="regular" aria-hidden />
              <span className="hidden sm:inline">Đăng xuất</span>
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6 sm:py-10">
        <Outlet />
      </main>
    </div>
  )
}
