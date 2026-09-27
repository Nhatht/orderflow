import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { useMutation } from '@tanstack/react-query'
import { ArrowRight, ArrowUDownLeft } from '@phosphor-icons/react'
import { login } from '../api/auth'
import { ApiError } from '../api/client'
import { selectIsAuthenticated, useAuthStore } from '../stores/authStore'
import { Button } from '../components/ui/Button'
import { Field } from '../components/ui/Field'
import { Wordmark } from '../components/layout/Wordmark'

const demoAccounts = [
  { username: 'alice', password: 'alice123' },
  { username: 'bob', password: 'bob123' },
]

export function LoginPage() {
  const isAuthenticated = useAuthStore(selectIsAuthenticated)
  const setSession = useAuthStore((s) => s.setSession)
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/products'

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')

  const mutation = useMutation({
    mutationFn: login,
    onSuccess: (res, req) => {
      setSession(res, req.username)
      navigate(from, { replace: true })
    },
  })

  if (isAuthenticated) return <Navigate to={from} replace />

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    mutation.mutate({ username: username.trim(), password })
  }

  function fillDemo(account: (typeof demoAccounts)[number]) {
    // reset() không huỷ request đang bay: đổi tài khoản giữa chừng sẽ đăng nhập nhầm người.
    if (mutation.isPending) return
    setUsername(account.username)
    setPassword(account.password)
    mutation.reset()
  }

  const errorMessage = describeLoginError(mutation.error)

  return (
    <div className="grid min-h-[100dvh] lg:grid-cols-[1.1fr_1fr]">
      <section className="hidden flex-col justify-between border-r border-border bg-surface-muted px-12 py-10 lg:flex">
        <Wordmark />
        <div className="max-w-md">
          <h1 className="text-4xl leading-[1.15] font-semibold tracking-tight text-balance">
            Đặt một đơn hàng, xem nó đi qua bốn service.
          </h1>
          <p className="mt-4 text-base leading-relaxed text-text-muted">
            Kho, thanh toán và đơn hàng nằm ở ba database riêng. Saga điều phối từng bước qua Kafka.
          </p>
          <ol className="mt-10 grid gap-3 text-sm">
            <FlowStep>Giữ hàng</FlowStep>
            <FlowStep>Thanh toán</FlowStep>
            <FlowStep>Xác nhận đơn</FlowStep>
          </ol>
          <p className="mt-5 flex items-start gap-2 text-sm text-text-muted">
            <ArrowUDownLeft size={16} className="mt-0.5 shrink-0 text-compensate" aria-hidden />
            Thanh toán thất bại thì hàng đã giữ được nhả lại.
          </p>
        </div>
        <p className="text-xs text-text-subtle">Dự án cá nhân. Spring Boot, Kafka, Redis, PostgreSQL, React.</p>
      </section>

      <section className="flex items-center justify-center px-4 py-12 sm:px-6">
        <div className="w-full max-w-sm">
          <div className="mb-8 lg:hidden">
            <Wordmark />
          </div>
          <h2 className="text-2xl font-semibold tracking-tight">Đăng nhập</h2>
          <p className="mt-1.5 text-sm text-text-muted">Dùng một tài khoản demo bên dưới.</p>

          <form onSubmit={handleSubmit} className="mt-8 grid gap-5" noValidate>
            <Field
              label="Tên đăng nhập"
              name="username"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
            />
            <Field
              label="Mật khẩu"
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
            {errorMessage && (
              <p role="alert" className="rounded-lg bg-fail-soft px-3 py-2.5 text-sm text-fail">
                {errorMessage}
              </p>
            )}
            <Button type="submit" loading={mutation.isPending} disabled={!username.trim() || !password}>
              Đăng nhập
            </Button>
          </form>

          <div className="mt-10 border-t border-border pt-6">
            <p className="text-sm font-medium">Tài khoản demo</p>
            <div className="mt-3 grid grid-cols-2 gap-2">
              {demoAccounts.map((a) => (
                <button
                  key={a.username}
                  type="button"
                  onClick={() => fillDemo(a)}
                  disabled={mutation.isPending}
                  className="rounded-lg disabled:cursor-not-allowed disabled:opacity-60 border border-border bg-surface px-3 py-2 text-left transition-colors hover:border-border-strong hover:bg-surface-muted"
                >
                  <span className="block text-sm font-medium">{a.username}</span>
                  <span className="num block text-xs text-text-muted">{a.password}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      </section>
    </div>
  )
}

function FlowStep({ children }: { children: string }) {
  return (
    <li className="flex items-center gap-3">
      <span className="grid size-7 place-items-center rounded-full border border-border-strong bg-surface text-text-muted">
        <ArrowRight size={13} aria-hidden />
      </span>
      <span className="font-medium">{children}</span>
    </li>
  )
}

function describeLoginError(error: Error | null): string | null {
  if (!error) return null
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Sai tên đăng nhập hoặc mật khẩu.'
    if (error.status === 429) return 'Thử lại quá nhiều lần. Đợi vài giây rồi đăng nhập lại.'
    if (error.status >= 500) return 'Gateway đang lỗi hoặc chưa chạy (cổng 8080).'
  }
  return error.message
}
