/*
 * Tiền ở frontend: số nguyên đã nhân tỉ lệ 10^4 (khớp NUMERIC(19,4) của backend),
 * cộng/nhân bằng BigInt. Giống cách lưu tiền bằng "minor units" (xu, cent).
 *
 * Vì sao không cộng thẳng number của JS: number là IEEE 754 double, 0.1 + 0.2
 * = 0.30000000000000004. Giá 25500.5 × 3 thì may mắn đúng, nhưng "may mắn" không
 * phải là thiết kế. Tổng ở đây chỉ là TẠM TÍNH để hiển thị; tổng chính thức là
 * totalAmount do order-service tính bằng BigDecimal.
 *
 * Phương án đã loại:
 * - Thư viện decimal (decimal.js, big.js): đúng, nhưng thêm một dependency cho đúng
 *   một phép cộng và một phép nhân với số nguyên.
 * - Math.round(price * 10000) rồi cộng bằng number: đúng tới 2^53 (khoảng 9 × 10^11
 *   đồng khi scale 10^4). BigInt không có giới hạn đó và cũng không đắt hơn.
 */

const SCALE = 10_000n
const SCALE_DIGITS = 4

/** Tiền dạng số nguyên đã nhân 10^4. Kiểu riêng để không lẫn với number thường. */
export type Minor = bigint

/**
 * number từ JSON → Minor. Giá backend có tối đa 4 chữ số thập phân (NUMERIC(19,4)),
 * nên nhân 10^4 rồi làm tròn trả về đúng số nguyên gốc: sai số của double
 * (vd. 0.1 * 10000 = 1000.0000000000001) nhỏ hơn 0,5 rất nhiều.
 */
export function toMinor(amount: number): Minor {
  return BigInt(Math.round(amount * 10_000))
}

export function lineTotal(unitPrice: Minor, quantity: number): Minor {
  return unitPrice * BigInt(quantity)
}

export function sum(values: Minor[]): Minor {
  return values.reduce((acc, v) => acc + v, 0n)
}

/** Minor → chuỗi thập phân chính xác, vd. 765015000n → "76501.5000". */
export function toDecimalString(value: Minor): string {
  const negative = value < 0n
  const abs = negative ? -value : value
  const whole = abs / SCALE
  const fraction = (abs % SCALE).toString().padStart(SCALE_DIGITS, '0')
  return `${negative ? '-' : ''}${whole}.${fraction}`
}

/**
 * Luật của cổng thanh toán giả lập (payment-service): PHẦN NGUYÊN của tổng tiền
 * tận cùng 99 → từ chối thẻ → saga huỷ đơn và nhả hàng đã giữ.
 */
export function isDemoDeclinedTotal(total: Minor): boolean {
  return (total / SCALE) % 100n === 99n
}
