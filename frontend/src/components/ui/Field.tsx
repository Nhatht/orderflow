import { useId, type InputHTMLAttributes } from 'react'
import { cn } from '../../lib/cn'

interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string
  error?: string
  hint?: string
}

/** Nhãn ở trên, lỗi ở dưới. Không dùng placeholder thay nhãn. */
export function Field({ label, error, hint, className, ...rest }: FieldProps) {
  const id = useId()
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  return (
    <div className="grid gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-text">
        {label}
      </label>
      <input
        id={id}
        {...rest}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          'h-10 rounded-lg border bg-surface px-3 text-sm text-text placeholder:text-text-subtle',
          'transition-[border-color,box-shadow] duration-150 focus:outline-none focus:ring-4 focus:ring-ring',
          error ? 'border-fail focus:border-fail' : 'border-border-strong focus:border-accent',
          className,
        )}
      />
      {error ? (
        <p id={`${id}-error`} className="text-sm text-fail">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="text-sm text-text-muted">
          {hint}
        </p>
      ) : null}
    </div>
  )
}
