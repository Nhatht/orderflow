import type { SagaStatus, SagaStep, SagaView, StepOutcome } from '../api/types'
import { isFinalSagaStatus } from '../api/orders'

/*
 * Dựng timeline từ SagaView.steps.
 *
 * steps là NHẬT KÝ append-only (bảng saga_step_log, sắp theo id BIGSERIAL): mỗi dòng là
 * một sự kiện "gửi lệnh" (REQUESTED) hoặc "nhận phản hồi" (SUCCEEDED / FAILED). Người
 * xem không cần đọc từng dòng log; họ cần thấy từng BƯỚC: đã hỏi lúc nào, trả lời ra
 * sao, sau bao lâu. Nên ở đây gộp mỗi cặp lệnh + phản hồi thành một "nút" timeline.
 *
 * Luật gộp (khớp với OrderSaga.java):
 * - REQUESTED → mở một nút mới cho bước đó.
 * - SUCCEEDED / FAILED → đóng nút ĐANG MỞ gần nhất của cùng bước. Không có nút mở thì
 *   tạo nút mới không có thời điểm gửi lệnh. Hai trường hợp thật:
 *   + CONFIRM_ORDER: bước nội bộ của order-service, ghi SUCCEEDED ngay, không có lệnh.
 *   + RESERVE_STOCK FAILED "RESERVATION_EXPIRED": inventory TỰ thu hồi phiếu khi saga
 *     đang chờ tiền. Bước giữ hàng đã thành công từ trước, nên đây là một nút mới.
 * - Nút còn mở khi saga đã ở trạng thái cuối = lệnh không bao giờ có phản hồi (vd. đã
 *   hỏi payment nhưng phiếu hết hạn trước). Hiển thị "bỏ dở", KHÔNG hiện là đang chờ.
 */

export type NodeState = 'pending' | 'succeeded' | 'failed' | 'abandoned' | 'upcoming'

export interface TimelineNode {
  /** Ổn định qua các lần poll (log chỉ thêm, không sửa): dùng làm React key. */
  key: string
  step: SagaStep
  state: NodeState
  /** Lúc saga gửi lệnh (dòng REQUESTED), nếu có. */
  requestedAt: string | null
  /** Lúc nhận phản hồi (dòng SUCCEEDED / FAILED), nếu có. */
  resolvedAt: string | null
  /** Mã lý do thất bại do service kia gửi về (CARD_DECLINED...). */
  detail: string | null
  /** Bước đền bù: hoàn tác tác dụng phụ của một bước đã thành công. */
  compensation: boolean
}

const FORWARD_STEPS: SagaStep[] = ['RESERVE_STOCK', 'PROCESS_PAYMENT', 'CONFIRM_ORDER']

export function buildTimeline(saga: SagaView): TimelineNode[] {
  const final = isFinalSagaStatus(saga.status)
  const nodes: TimelineNode[] = []

  saga.steps.forEach((entry, index) => {
    if (entry.outcome === 'REQUESTED') {
      nodes.push(newNode(entry.step, index, entry.occurredAt, null, 'pending', null))
      return
    }
    const resolved = toResolvedState(entry.outcome)
    const open = findLastOpen(nodes, entry.step)
    if (open) {
      open.state = resolved
      open.resolvedAt = entry.occurredAt
      open.detail = entry.detail ?? null
    } else {
      nodes.push(newNode(entry.step, index, null, entry.occurredAt, resolved, entry.detail ?? null))
    }
  })

  if (final) {
    for (const n of nodes) if (n.state === 'pending') n.state = 'abandoned'
    return nodes
  }

  // Saga còn đang chạy theo chiều thuận: cho người xem thấy những bước CÒN LẠI của kế
  // hoạch (mờ), để biết saga đang ở đâu trên cả đường đi. Khi đang đền bù thì không:
  // các bước thuận phía sau sẽ không bao giờ chạy nữa.
  if (saga.status === 'STARTED' || saga.status === 'AWAITING_PAYMENT') {
    const seen = new Set(nodes.map((n) => n.step))
    for (const step of FORWARD_STEPS) {
      if (!seen.has(step)) {
        nodes.push({
          key: `upcoming-${step}`,
          step,
          state: 'upcoming',
          requestedAt: null,
          resolvedAt: null,
          detail: null,
          compensation: false,
        })
      }
    }
  }
  return nodes
}

function newNode(
  step: SagaStep,
  index: number,
  requestedAt: string | null,
  resolvedAt: string | null,
  state: NodeState,
  detail: string | null,
): TimelineNode {
  return {
    // Chỉ số dòng log đầu tiên của nút: log append-only nên chỉ số không bao giờ đổi.
    key: `${step}-${index}`,
    step,
    state,
    requestedAt,
    resolvedAt,
    detail,
    compensation: step === 'RELEASE_STOCK',
  }
}

function toResolvedState(outcome: StepOutcome): NodeState {
  return outcome === 'SUCCEEDED' ? 'succeeded' : 'failed'
}

function findLastOpen(nodes: TimelineNode[], step: SagaStep): TimelineNode | undefined {
  for (let i = nodes.length - 1; i >= 0; i--) {
    const n = nodes[i]!
    if (n.step === step && n.state === 'pending') return n
  }
  return undefined
}

// ---- Chữ hiển thị -----------------------------------------------------------

/** Tên bước + service thực hiện: người xem demo thấy luôn lệnh đi tới đâu. */
export const stepCopy: Record<SagaStep, { title: string; service: string }> = {
  RESERVE_STOCK: { title: 'Giữ hàng', service: 'inventory-service' },
  PROCESS_PAYMENT: { title: 'Thu tiền', service: 'payment-service' },
  CONFIRM_ORDER: { title: 'Xác nhận đơn', service: 'order-service' },
  RELEASE_STOCK: { title: 'Nhả hàng về kho', service: 'inventory-service' },
}

export type Tone = 'neutral' | 'active' | 'ok' | 'fail' | 'compensate'

/** Lớp màu (nền nhạt + chữ) cho từng tông ngữ nghĩa: nhãn trạng thái, hộp lý do. */
export const toneClasses: Record<Tone, string> = {
  neutral: 'bg-surface-muted text-text-muted',
  active: 'bg-accent-soft text-accent',
  ok: 'bg-ok-soft text-ok',
  fail: 'bg-fail-soft text-fail',
  compensate: 'bg-compensate-soft text-compensate',
}

/**
 * Trạng thái saga tổng. Màu theo ngữ nghĩa (tuần 9): đang chạy = cobalt, xong = ok,
 * đền bù = hổ phách, thất bại không cần đền bù = fail.
 *
 * COMPENSATED dùng hổ phách chứ không dùng đỏ: đơn bị huỷ, nhưng điều saga muốn nói là
 * "tác dụng phụ đã được HOÀN TÁC". Khác FAILED (chưa có gì để hoàn tác). Backend tách
 * hai trạng thái này có chủ đích (SagaStatus.java), giao diện giữ đúng khác biệt đó.
 */
export const sagaStatusCopy: Record<SagaStatus, { label: string; tone: Tone; summary: string }> = {
  STARTED: {
    label: 'Đang giữ hàng',
    tone: 'active',
    summary: 'Đã gửi lệnh giữ hàng tới inventory-service qua Kafka, đang chờ phản hồi.',
  },
  AWAITING_PAYMENT: {
    label: 'Đang chờ thanh toán',
    tone: 'active',
    summary: 'Hàng đã được giữ. Đã gửi lệnh thu tiền tới payment-service, đang chờ phản hồi.',
  },
  COMPLETED: {
    label: 'Hoàn tất',
    tone: 'ok',
    summary: 'Đã giữ hàng, thu tiền và xác nhận đơn.',
  },
  COMPENSATING: {
    label: 'Đang đền bù',
    tone: 'compensate',
    summary: 'Một bước đã thất bại sau khi hàng được giữ. Saga đang yêu cầu nhả hàng về kho.',
  },
  COMPENSATED: {
    label: 'Đã đền bù',
    tone: 'compensate',
    summary: 'Đơn bị huỷ. Hàng đã giữ được trả về kho, không còn tác dụng phụ nào.',
  },
  FAILED: {
    label: 'Thất bại',
    tone: 'fail',
    // Hai đường tới FAILED (OrderSaga.java): hết hàng (chưa giữ gì), hoặc kho TỰ thu hồi
    // phiếu hết hạn (đã giữ, nhưng hàng đã về kho). Câu này phải đúng cho cả hai.
    summary: 'Đơn bị huỷ. Không còn hàng nào đang giữ cho đơn này, nên saga không cần bước đền bù.',
  },
}

/**
 * Mã lý do thật từ backend (không đoán):
 * - INSUFFICIENT_STOCK, UNKNOWN_PRODUCT: StockReservationFailedEvent.Reason (inventory).
 * - CARD_DECLINED: SimulatedPaymentGateway (payment), tổng có phần nguyên tận cùng 99.
 * - PAYMENT_TIMEOUT: OrderSaga.paymentTimedOut(), saga timeout PT3M (order-service).
 * - RESERVATION_EXPIRED: SagaReplyListener khi nhận stock.reservation-expired.
 * Mã lạ (cổng thanh toán thật có thể trả bất kỳ chuỗi nào) → câu chung kèm mã gốc.
 */
const failureReasons: Record<string, string> = {
  INSUFFICIENT_STOCK: 'Kho không đủ hàng cho ít nhất một sản phẩm trong đơn.',
  UNKNOWN_PRODUCT: 'Đơn có sản phẩm không tồn tại trong kho.',
  CARD_DECLINED: 'Cổng thanh toán từ chối thẻ. (Cổng giả lập từ chối mọi tổng có phần nguyên tận cùng 99.)',
  PAYMENT_TIMEOUT: 'Quá 3 phút chưa có kết quả thanh toán, saga tự huỷ đơn để không giữ hàng mãi.',
  RESERVATION_EXPIRED: 'Phiếu giữ hàng hết hạn trước khi thanh toán xong. Kho đã tự thu hồi hàng.',
}

const shortReasons: Record<string, string> = {
  INSUFFICIENT_STOCK: 'không đủ hàng',
  UNKNOWN_PRODUCT: 'sản phẩm không tồn tại',
  CARD_DECLINED: 'thẻ bị từ chối',
  PAYMENT_TIMEOUT: 'quá hạn chờ thanh toán',
  RESERVATION_EXPIRED: 'phiếu giữ hàng hết hạn',
}

/** Bản ngắn để đặt trong dòng của từng bước; bản đầy đủ nằm ở đầu timeline. */
export function shortFailureReason(code: string): string {
  return shortReasons[code] ?? 'lỗi không xác định'
}

export function describeFailureReason(code: string): string {
  return failureReasons[code] ?? `Service phía sau báo lỗi với mã ${code}.`
}

// ---- Thời gian --------------------------------------------------------------

export function msBetween(fromIso: string, toIso: string): number {
  return new Date(toIso).getTime() - new Date(fromIso).getTime()
}

const oneDecimal = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 })
const twoDecimals = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 })

/**
 * Khoảng thời gian cho người đọc: "12 ms", "1,24 s", "3 phút 5 s".
 * Độ phân giải của Date là mili giây (backend lưu micro giây, JS cắt bớt). Đủ cho
 * mục đích ở đây: thấy các bước cách nhau hàng trăm ms vì đi qua Kafka + outbox.
 */
export function formatDuration(ms: number): string {
  // Làm tròn TRƯỚC rồi mới chọn đơn vị, nếu không sẽ ra "1000 ms", "60 s", "1 phút 60 s".
  const v = Math.round(Math.max(0, ms))
  if (v < 1000) return `${v} ms`
  if (v < 9_995) return `${twoDecimals.format(v / 1000)} s`
  if (v < 59_950) return `${oneDecimal.format(v / 1000)} s`
  const totalSeconds = Math.round(v / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return seconds === 0 ? `${minutes} phút` : `${minutes} phút ${seconds} s`
}
