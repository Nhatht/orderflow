import { toDecimalString, type Minor } from './money'

const moneyFormatters = new Map<string, Intl.NumberFormat>()

function moneyFormatter(currency: string): Intl.NumberFormat {
  let fmt = moneyFormatters.get(currency)
  if (!fmt) {
    try {
      fmt = new Intl.NumberFormat('vi-VN', { style: 'currency', currency, maximumFractionDigits: 2 })
    } catch {
      // Mã tiền tệ lạ, hoặc tiền tệ mặc định 3 chữ số lẻ (KWD) xung đột với maximumFractionDigits:
      // Intl ném RangeError. Một đơn dữ liệu lạ không được làm trắng cả trang.
      fmt = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 4 })
    }
    moneyFormatters.set(currency, fmt)
  }
  return fmt
}

/** Chỉ để hiển thị. Không dùng kết quả này (hay number gốc) để tính tiền. */
export function formatMoney(amount: number, currency: string): string {
  return moneyFormatter(currency).format(amount)
}

/**
 * Hiển thị tiền đã tính bằng BigInt mà không quay về number: Intl.NumberFormat
 * nhận chuỗi thập phân và định dạng CHÍNH XÁC (ES2023, "Intl.NumberFormat v3").
 * Kiểu TS của dự án (lib ES2022) chưa khai báo overload nhận chuỗi, nên phải ép kiểu.
 */
export function formatMinor(value: Minor, currency: string): string {
  return moneyFormatter(currency).format(toDecimalString(value) as unknown as number)
}

const dateTime = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short', timeStyle: 'medium' })

export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso))
}

const clock = new Intl.DateTimeFormat('vi-VN', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  fractionalSecondDigits: 3,
  hour12: false,
})

/** Giờ kèm mili giây (14:37:27.016): timeline saga cần thấy chênh lệch dưới một giây. */
export function formatClock(iso: string): string {
  return clock.format(new Date(iso))
}

/** 8 ký tự đầu của UUID — đủ để người dùng nhận ra đơn. */
export function shortId(id: string): string {
  return id.slice(0, 8)
}
