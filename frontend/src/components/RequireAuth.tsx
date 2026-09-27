import { useEffect } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { selectIsAuthenticated, useAuthStore } from '../stores/authStore'

/** Route guard: chưa đăng nhập → về /login, nhớ trang định vào để quay lại sau. */
export function RequireAuth() {
  const isAuthenticated = useAuthStore(selectIsAuthenticated)
  const hasStaleToken = useAuthStore((s) => s.accessToken !== null)
  const logout = useAuthStore((s) => s.logout)
  const location = useLocation()

  // Token còn trong store nhưng đã hết hạn: xoá hẳn phiên (và cache, qua subscribe ở main.tsx).
  useEffect(() => {
    if (!isAuthenticated && hasStaleToken) logout()
  }, [isAuthenticated, hasStaleToken, logout])

  if (!isAuthenticated) {
    const from = location.pathname + location.search + location.hash
    return <Navigate to="/login" replace state={{ from }} />
  }
  return <Outlet />
}
