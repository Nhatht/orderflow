import { useState, type ReactNode } from 'react'
import {
  ArrowUDownLeft,
  CheckCircle,
  Circle,
  CircleNotch,
  MinusCircle,
  XCircle,
} from '@phosphor-icons/react'
import type { SagaView } from '../../api/types'
import { isFinalSagaStatus } from '../../api/orders'
import {
  buildTimeline,
  describeFailureReason,
  shortFailureReason,
  formatDuration,
  msBetween,
  sagaStatusCopy,
  stepCopy,
  toneClasses,
  type TimelineNode,
} from '../../lib/saga'
import { formatClock } from '../../lib/format'
import { cn } from '../../lib/cn'
import { SagaStatusBadge } from '../ui/StatusBadge'

interface SagaTimelineProps {
  saga: SagaView
  /** Lần poll gần nhất lỗi (mạng, 429, 5xx) nhưng vẫn còn dữ liệu cũ để hiển thị. */
  stale: boolean
}

/**
 * Timeline saga: trạng thái tổng ở trên, từng bước (lệnh + phản hồi) ở dưới.
 * Cột trái là thời gian kể từ khi saga bắt đầu, để thấy các bước cách nhau hàng trăm
 * mili giây: mỗi mũi tên là một vòng Kafka + outbox, không phải một lời gọi hàm.
 */
export function SagaTimeline({ saga, stale }: SagaTimelineProps) {
  const nodes = buildTimeline(saga)
  const final = isFinalSagaStatus(saga.status)
  const status = sagaStatusCopy[saga.status]
  // Mốc 0 của cột thời gian: dòng log ĐẦU TIÊN, không phải saga.startedAt. occurredAt do
  // Postgres ghi (clock_timestamp()), startedAt do JVM ghi (Instant.now()): hai đồng hồ
  // khác máy (container vs host) có thể lệch nhau vài giây, trừ lẫn nhau ra số vô nghĩa.
  const origin = saga.steps[0]?.occurredAt ?? saga.startedAt

  // Nút có mặt ở lần hiển thị đầu tiên thì không chạy hiệu ứng (mở lại đơn cũ không
  // cần "diễn" lại). Chỉ nút xuất hiện SAU đó, tức là saga vừa tiến thêm một bước
  // trong lúc đang xem, mới trượt vào.
  const [initialKeys] = useState(() => new Set(nodes.map((n) => n.key)))

  return (
    <section aria-labelledby="saga-heading" className="rounded-xl border border-border bg-surface shadow-card">
      <header className="border-b border-border px-5 py-4 sm:px-6">
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <div className="flex items-center gap-3">
            <h2 id="saga-heading" className="font-semibold">
              Saga
            </h2>
            <SagaStatusBadge status={saga.status} />
          </div>
          <LiveState final={final} stale={stale} saga={saga} />
        </div>
        {/* aria-live: trình đọc màn hình đọc câu này mỗi khi saga đổi trạng thái. */}
        <p aria-live="polite" className="mt-2 text-sm text-text-muted">
          {status.summary}
        </p>
        {saga.failureReason && (
          <div className={cn('mt-3 rounded-lg px-3 py-2.5 text-sm', toneClasses[status.tone])}>
            <p>
              <span className="font-medium">Lý do: </span>
              {describeFailureReason(saga.failureReason)}
            </p>
            <p className="num mt-0.5 text-xs opacity-80">{saga.failureReason}</p>
          </div>
        )}
      </header>

      <ol className="px-5 py-5 sm:px-6">
        {nodes.map((node, i) => (
          <TimelineItem
            key={node.key}
            node={node}
            origin={origin}
            last={i === nodes.length - 1}
            nextIsCompensation={nodes[i + 1]?.compensation ?? false}
            nextIsUpcoming={nodes[i + 1]?.state === 'upcoming'}
            animate={!initialKeys.has(node.key)}
          />
        ))}
      </ol>
      <p className="border-t border-border px-5 py-3 text-xs text-text-subtle sm:px-6">
        Cột trái: thời gian kể từ khi saga bắt đầu. Mỗi bước là một lệnh gửi qua Kafka và một phản hồi quay về.
      </p>
    </section>
  )
}

function LiveState({ final, stale, saga }: { final: boolean; stale: boolean; saga: SagaView }) {
  if (stale) {
    return <span className="text-xs font-medium text-fail">Mất kết nối, đang thử lại</span>
  }
  if (final) {
    return (
      <span className="text-xs text-text-muted">
        Kết thúc sau <span className="num text-text">{formatDuration(msBetween(saga.startedAt, saga.updatedAt))}</span>
      </span>
    )
  }
  // Chấm tròn ở đây mang trạng thái THẬT: trang đang poll saga mỗi giây.
  return (
    <span className="inline-flex items-center gap-2 text-xs text-text-muted">
      <span className="relative flex size-2" aria-hidden>
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-accent opacity-60" />
        <span className="relative inline-flex size-2 rounded-full bg-accent" />
      </span>
      Đang theo dõi, cập nhật mỗi giây
    </span>
  )
}

interface TimelineItemProps {
  node: TimelineNode
  /** Mốc 0 của cột thời gian (occurredAt của dòng log đầu tiên). */
  origin: string
  last: boolean
  nextIsCompensation: boolean
  nextIsUpcoming: boolean
  animate: boolean
}

function TimelineItem({ node, origin, last, nextIsCompensation, nextIsUpcoming, animate }: TimelineItemProps) {
  const copy = stepCopy[node.step]
  const at = node.requestedAt ?? node.resolvedAt
  const upcoming = node.state === 'upcoming'

  return (
    <li className={cn('grid grid-cols-[3.75rem_1.5rem_1fr] gap-x-3 sm:grid-cols-[4.5rem_1.5rem_1fr]', animate && 'step-enter')}>
      {/* Cột 1: thời điểm tương đối so với lúc saga bắt đầu. */}
      <span className={cn('num text-right text-xs text-text-subtle', node.compensation ? 'pt-3' : 'pt-0.5')}>
        {at ? `+${formatDuration(msBetween(origin, at))}` : ''}
      </span>

      {/* Cột 2: icon trạng thái + đường nối xuống bước sau. */}
      <div className={cn('flex flex-col items-center', node.compensation && 'pt-2.5')}>
        <NodeIcon node={node} />
        {!last && (
          <span
            aria-hidden
            className={cn(
              'my-1 w-px flex-1',
              nextIsUpcoming ? 'border-l border-dashed border-border-strong' : nextIsCompensation ? 'bg-compensate' : 'bg-border-strong',
            )}
          />
        )}
      </div>

      {/* Cột 3: nội dung bước. */}
      <div
        className={cn(
          'min-w-0',
          !last && 'mb-6',
          // Nhánh đền bù: nền hổ phách nhạt cả khối, nhìn là biết đây là đường lùi.
          node.compensation && 'rounded-lg bg-compensate-soft px-3 py-2.5',
        )}
      >
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <h3 className={cn('font-medium', upcoming && 'text-text-subtle')}>{copy.title}</h3>
          {node.compensation && (
            <span className="inline-flex h-5 items-center gap-1 rounded-full border border-compensate/40 px-2 text-xs font-medium text-compensate">
              <ArrowUDownLeft size={12} weight="bold" aria-hidden />
              Đền bù
            </span>
          )}
          <span className="num text-xs text-text-subtle">{copy.service}</span>
        </div>
        <p className={cn('mt-0.5 text-sm', stateTextClass(node))}>
          {stateLabel(node)}
          {node.state === 'failed' && node.detail && (
            <span className="num ml-2 text-xs text-text-subtle">{node.detail}</span>
          )}
        </p>
        <Timing node={node} />
      </div>
    </li>
  )
}

function Timing({ node }: { node: TimelineNode }) {
  if (node.state === 'upcoming') return null
  const parts: ReactNode[] = []
  if (node.requestedAt) {
    parts.push(
      <span key="req">
        Gửi lệnh <span className="num">{formatClock(node.requestedAt)}</span>
      </span>,
    )
  }
  if (node.requestedAt && node.resolvedAt) {
    parts.push(
      <span key="dur">
        phản hồi sau <span className="num text-text">{formatDuration(msBetween(node.requestedAt, node.resolvedAt))}</span>
      </span>,
    )
  } else if (node.resolvedAt) {
    parts.push(
      <span key="res">
        Ghi nhận <span className="num">{formatClock(node.resolvedAt)}</span>
      </span>,
    )
  }
  return <p className="mt-1.5 text-xs text-text-subtle">{joinWithComma(parts)}</p>
}

function joinWithComma(parts: ReactNode[]): ReactNode[] {
  return parts.flatMap((p, i) => (i === 0 ? [p] : [<span key={`sep-${i}`}>, </span>, p]))
}

function stateLabel(node: TimelineNode): string {
  const service = stepCopy[node.step].service
  switch (node.state) {
    case 'pending':
      return `Đang chờ ${service} phản hồi`
    case 'succeeded':
      if (node.compensation) return 'Hàng đã giữ cho đơn này được trả về kho.'
      if (node.step === 'CONFIRM_ORDER') return 'Đơn đã được xác nhận'
      return 'Thành công'
    case 'failed': {
      const reason = node.detail ? `: ${shortFailureReason(node.detail)}` : ''
      // Không có lệnh đi trước: service kia TỰ báo (vd. kho tự thu hồi phiếu hết hạn).
      return node.requestedAt ? `Thất bại${reason}` : `${service} tự báo thất bại${reason}`
    }
    case 'abandoned':
      return 'Không có phản hồi: saga đã kết thúc trước khi bước này trả lời'
    case 'upcoming':
      return 'Chưa tới'
  }
}

function stateTextClass(node: TimelineNode): string {
  switch (node.state) {
    case 'pending':
      return node.compensation ? 'text-compensate' : 'text-accent'
    case 'succeeded':
      return node.compensation ? 'text-compensate' : 'text-ok'
    case 'failed':
      return 'text-fail'
    case 'abandoned':
    case 'upcoming':
      return 'text-text-subtle'
  }
}

function NodeIcon({ node }: { node: TimelineNode }) {
  const common = 'shrink-0 transition-colors duration-300'
  switch (node.state) {
    case 'pending':
      return (
        <CircleNotch
          size={22}
          weight="bold"
          aria-label="Đang chờ"
          className={cn(common, 'animate-spin', node.compensation ? 'text-compensate' : 'text-accent')}
        />
      )
    case 'succeeded':
      return node.compensation ? (
        <span aria-label="Đã đền bù" className={cn(common, 'grid size-[22px] place-items-center rounded-full bg-compensate text-surface')}>
          <ArrowUDownLeft size={13} weight="bold" aria-hidden />
        </span>
      ) : (
        <CheckCircle size={22} weight="fill" aria-label="Thành công" className={cn(common, 'text-ok')} />
      )
    case 'failed':
      return <XCircle size={22} weight="fill" aria-label="Thất bại" className={cn(common, 'text-fail')} />
    case 'abandoned':
      return <MinusCircle size={22} aria-label="Bỏ dở" className={cn(common, 'text-text-subtle')} />
    case 'upcoming':
      return <Circle size={22} aria-label="Chưa tới" className={cn(common, 'text-border-strong')} />
  }
}
