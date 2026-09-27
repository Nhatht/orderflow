import { ArrowRight } from '@phosphor-icons/react'

export function Wordmark() {
  return (
    <span className="inline-flex items-center gap-2 text-[15px] font-semibold tracking-tight text-text">
      <span className="grid size-6 place-items-center rounded-md bg-accent text-accent-fg">
        <ArrowRight size={14} weight="bold" aria-hidden />
      </span>
      OrderFlow
    </span>
  )
}
