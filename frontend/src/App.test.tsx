import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { adminApi } from '@/api/endpoints'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import { App } from './App'

vi.mock('@/auth/AuthProvider', () => ({
  useAuth: () => ({
    me: { id: 9, username: 'admin', name: '관리자', role: 'ADMIN', createdAt: '2026-10-01T00:00:00Z' },
    loading: false,
    logout: vi.fn(),
  }),
}))
vi.mock('@/api/endpoints', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/endpoints')>()
  return { ...actual, adminApi: { accounts: vi.fn(), deleteAccount: vi.fn() } }
})

describe('App', () => {
  it('[ADM-12] 관리 전용 계정은 드라이브 주소로 와도 계정 관리 화면으로 간다 — 드라이브·팀이 없음', async () => {
    vi.mocked(adminApi.accounts).mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/drive']}>
          <ToastProvider>
            <ConfirmProvider>
              <App />
            </ConfirmProvider>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('heading', { name: '계정 관리' })).toBeInTheDocument()
    expect(await screen.findByText('가입한 계정이 없습니다')).toBeInTheDocument()
  })
})
