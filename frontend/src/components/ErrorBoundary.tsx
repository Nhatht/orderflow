import { Component, type ErrorInfo, type ReactNode } from 'react'
import { WarningCircle } from '@phosphor-icons/react'
import { Button } from './ui/Button'

interface State {
  error: Error | null
}

/**
 * Lưới cuối cho lỗi render (vd. dữ liệu lạ từ API): hiện thông báo thay vì trang trắng.
 * React chỉ hỗ trợ error boundary bằng class component.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[OrderFlow] render error', error, info.componentStack)
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <div className="grid min-h-[100dvh] place-items-center px-4">
        <div className="flex max-w-md flex-col items-start gap-3 rounded-xl border border-border bg-surface p-6">
          <p className="flex items-center gap-2 font-medium text-fail">
            <WarningCircle size={18} aria-hidden />
            Trang gặp lỗi khi hiển thị
          </p>
          <p className="text-sm text-text-muted">{this.state.error.message}</p>
          <Button variant="secondary" onClick={() => window.location.reload()}>
            Tải lại trang
          </Button>
        </div>
      </div>
    )
  }
}
