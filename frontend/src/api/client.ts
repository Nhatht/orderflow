import { useAuthStore } from '../stores/authStore'
import type { ErrorResponse } from './types'

/** Lỗi HTTP đã chuẩn hoá: component chỉ cần xem `status` / `code`, không đụng Response. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: NonNullable<ErrorResponse['errors']>

  constructor(status: number, body?: Partial<ErrorResponse>) {
    super(body?.message || defaultMessage(status))
    this.name = 'ApiError'
    this.status = status
    this.code = body?.code ?? `HTTP_${status}`
    this.fieldErrors = body?.errors ?? []
  }
}

const MAX_RATE_LIMIT_RETRIES = 2
const RATE_LIMIT_BACKOFF_MS = 500

/**
 * Một chỗ duy nhất gắn token và chuẩn hoá lỗi (giống DelegatingHandler của HttpClient).
 * - 401 trên request có token: token hết hạn / sai → logout, RequireAuth đẩy về /login.
 * - 429: rate limit của gateway (10 req/s, dồn 20) → chờ rồi thử lại tối đa 2 lần.
 */
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = currentToken(path)
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let res: Response
  for (let attempt = 0; ; attempt++) {
    try {
      res = await fetch(path, { ...init, headers })
    } catch {
      // fetch chỉ ném khi không tới được server (mạng, proxy Vite không nối được gateway).
      throw new ApiError(0, { code: 'NETWORK_ERROR', message: 'Không kết nối được tới máy chủ.' })
    }
    if (res.status !== 429 || attempt >= MAX_RATE_LIMIT_RETRIES) break
    await sleep(RATE_LIMIT_BACKOFF_MS * (attempt + 1))
  }

  const body = await readBody(res)

  if (!res.ok) {
    // Chỉ logout nếu token lúc gửi vẫn là token hiện tại: tránh một response 401 đến muộn
    // của phiên cũ đăng xuất phiên vừa đăng nhập lại.
    if (res.status === 401 && token && useAuthStore.getState().accessToken === token) {
      useAuthStore.getState().logout()
    }
    throw new ApiError(res.status, isErrorResponse(body) ? body : undefined)
  }
  return body as T
}

/**
 * Token để gắn vào request, hoặc null.
 * - /auth/* không bao giờ mang token: gateway là OAuth2 resource server, bearer hỏng
 *   (vd. đã hết hạn) bị trả 401 kể cả trên route permitAll, nên đăng nhập lại sẽ báo sai mật khẩu.
 * - Token đã hết hạn thì xoá phiên luôn thay vì gửi đi để nhận 401.
 */
function currentToken(path: string): string | null {
  if (path.startsWith('/auth/')) return null
  const { accessToken, expiresAt, logout } = useAuthStore.getState()
  if (accessToken && expiresAt !== null && expiresAt <= Date.now()) {
    logout()
    return null
  }
  return accessToken
}

function defaultMessage(status: number): string {
  if (status === 429) return 'Quá nhiều yêu cầu. Đợi vài giây rồi thử lại.'
  if (status >= 500) return 'Máy chủ đang lỗi hoặc chưa chạy.'
  return `Lỗi HTTP ${status}`
}

/** Gateway trả 401 không có thân, 204 cũng rỗng: đọc text trước rồi mới parse. */
async function readBody(res: Response): Promise<unknown> {
  const text = await res.text()
  if (!text) return undefined
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

function isErrorResponse(body: unknown): body is Partial<ErrorResponse> {
  return typeof body === 'object' && body !== null && 'message' in body
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
