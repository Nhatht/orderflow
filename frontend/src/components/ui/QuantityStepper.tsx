import type { ButtonHTMLAttributes } from 'react'
import { Minus, Plus } from '@phosphor-icons/react'
import { cn } from '../../lib/cn'

interface QuantityStepperProps {
  value: number
  max: number
  onChange: (next: number) => void
  /** Tên sản phẩm, cho aria-label ("Giảm số lượng Kẹo dừa"). */
  label: string
  /** Ở 1 mà bấm "-": gọi hàm này (vd. xoá khỏi giỏ) thay vì chặn. */
  onRemove?: () => void
  className?: string
}

/** [-] 2 [+]. Nút "+" tắt khi chạm số còn bán, để không bao giờ gửi đơn vượt tồn kho. */
export function QuantityStepper({ value, max, onChange, label, onRemove, className }: QuantityStepperProps) {
  const canDecrease = value > 1 || onRemove !== undefined
  return (
    <div
      className={cn(
        'inline-flex h-9 items-center rounded-lg border border-border-strong bg-surface',
        className,
      )}
    >
      <StepButton
        aria-label={`Giảm số lượng ${label}`}
        disabled={!canDecrease}
        onClick={() => (value > 1 ? onChange(value - 1) : onRemove?.())}
      >
        <Minus size={14} aria-hidden />
      </StepButton>
      <span className="num min-w-8 text-center text-sm font-medium" aria-live="polite">
        {value}
      </span>
      <StepButton
        aria-label={`Tăng số lượng ${label}`}
        disabled={value >= max}
        onClick={() => onChange(value + 1)}
      >
        <Plus size={14} aria-hidden />
      </StepButton>
    </div>
  )
}

function StepButton(props: ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      type="button"
      {...props}
      className="grid h-full w-9 place-items-center rounded-lg text-text-muted transition-colors hover:bg-surface-muted hover:text-text active:translate-y-px disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
    />
  )
}
