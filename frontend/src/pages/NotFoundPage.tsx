import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <div className="grid min-h-[60dvh] place-items-center text-center">
      <div>
        <p className="num text-sm text-text-muted">404</p>
        <h1 className="mt-2 text-2xl font-semibold tracking-tight">Không có trang này</h1>
        <Link to="/orders" className="mt-6 inline-block text-sm font-medium text-accent hover:underline">
          Về danh sách đơn
        </Link>
      </div>
    </div>
  )
}
