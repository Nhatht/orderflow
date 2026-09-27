import { ApiError } from '../api/client'

/**
 * Lỗi API → câu cho người dùng. `service` là tên service phía sau gateway, để lỗi 5xx
 * nói rõ cần bật cái gì (demo chạy từng service bằng tay, hay quên một cái).
 */
export function describeApiError(error: Error, service: string): string {
  if (error instanceof ApiError) {
    if (error.status === 0) return 'Không kết nối được tới máy chủ. Kiểm tra gateway (cổng 8080) và Vite proxy.'
    if (error.status === 429) return 'Gửi quá nhiều yêu cầu trong một giây. Đợi một lát rồi thử lại.'
    if (error.status >= 500) return `${service} đang lỗi hoặc chưa chạy.`
  }
  return error.message
}
