import { apiFetch } from './client'
import type { LoginRequest, LoginResponse } from './types'

/** Sai mật khẩu → ApiError status 401 (không logout vì request này không mang token). */
export function login(req: LoginRequest): Promise<LoginResponse> {
  return apiFetch<LoginResponse>('/auth/login', { method: 'POST', body: JSON.stringify(req) })
}
