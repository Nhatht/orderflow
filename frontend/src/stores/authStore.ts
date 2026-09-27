import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import type { LoginResponse } from '../api/types'

export interface AuthState {
  accessToken: string | null
  username: string | null
  /** Thời điểm hết hạn, epoch millis (tính từ expiresIn lúc đăng nhập). */
  expiresAt: number | null
  setSession: (res: LoginResponse, username: string) => void
  logout: () => void
}

/*
 * Token lưu ở sessionStorage: F5 không mất phiên, đóng tab là mất.
 * Tạm thời, vì gateway chưa có refresh token. Khi có /auth/refresh + cookie
 * HttpOnly thì bỏ persist, chỉ giữ access token trong bộ nhớ (docs/AUTH-NOTES.md mục 2).
 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      username: null,
      expiresAt: null,
      setSession: (res, username) =>
        set({
          accessToken: res.accessToken,
          username,
          expiresAt: Date.now() + res.expiresIn * 1000,
        }),
      logout: () => set({ accessToken: null, username: null, expiresAt: null }),
    }),
    {
      name: 'orderflow-auth',
      storage: createJSONStorage(() => sessionStorage),
      partialize: (s) => ({ accessToken: s.accessToken, username: s.username, expiresAt: s.expiresAt }),
    },
  ),
)

/** Đã đăng nhập và token chưa hết hạn. */
export function selectIsAuthenticated(s: AuthState): boolean {
  return s.accessToken !== null && s.expiresAt !== null && s.expiresAt > Date.now()
}
