import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { adminApi } from '@/api/endpoints'
import type { AdminAccountPage } from '@/api/types'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import AdminPage from './AdminPage'

vi.mock('@/auth/AuthProvider', () => ({
  useAuth: () => ({ me: { id: 9, username: 'admin', name: '관리자', role: 'ADMIN', createdAt: '2026-10-01T00:00:00Z' }, logout: vi.fn() }),
}))
vi.mock('@/api/endpoints', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/endpoints')>()
  return { ...actual, adminApi: { accounts: vi.fn(), deleteAccount: vi.fn() } }
})

const PAGE: AdminAccountPage = {
  items: [
    {
      id: 21, username: 'kim', name: '김민수', storedBytes: 1536, quotaBytes: 1073741824, createdAt: '2026-10-12T03:00:00Z', demo: false,
      teams: [{ name: '디자인팀', leader: true }, { name: '마케팅팀', leader: false }],
    },
    { id: 3, username: 'demo1', name: '김하늘', storedBytes: 0, quotaBytes: 52428800, createdAt: '2026-10-01T00:00:00Z', demo: true, teams: [] },
  ],
  page: 0, size: 20, totalElements: 2, totalPages: 1,
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastProvider>
          <ConfirmProvider>
            <AdminPage />
          </ConfirmProvider>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.mocked(adminApi.accounts).mockResolvedValue(PAGE)
  vi.mocked(adminApi.deleteAccount).mockResolvedValue({ transferredTeams: 1, deletedTeams: 0 })
})

describe('AdminPage', () => {
  it('[ADM-12] 계정마다 아이디·이름·소속 팀(팀장 표시)·드라이브 사용량·가입일을 보이고, 체험 계정은 삭제할 수 없다', async () => {
    renderPage()

    const row = (await screen.findByText('kim')).closest('tr')!
    expect(within(row).getByText('김민수')).toBeInTheDocument()
    expect(within(row).getByText('디자인팀')).toBeInTheDocument()
    expect(within(row).getByText('팀장')).toBeInTheDocument()
    expect(within(row).getByText('마케팅팀')).toBeInTheDocument()
    expect(row).toHaveTextContent('1.5 KB / 1.0 GB')
    expect(row).toHaveTextContent('2026')
    expect(within(row).getByRole('button', { name: 'kim 계정 삭제' })).toBeEnabled()

    const demo = screen.getByText('demo1').closest('tr')!
    expect(within(demo).getByText('체험 계정')).toBeInTheDocument()
    expect(within(demo).getByText('없음')).toBeInTheDocument()
    expect(within(demo).getByRole('button', { name: 'demo1 계정 삭제' })).toBeDisabled()
    expect(screen.getByText('전체 2명')).toBeInTheDocument()
  })

  it('[ADM-12] 삭제는 아이디를 입력해야 확인되고, 팀장인 팀의 처리 방법을 미리 알린 뒤 결과를 알린다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'kim 계정 삭제' }))
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent('팀장인 팀(디자인팀)은 가장 먼저 들어온 멤버에게 팀장을 넘기고')
    const confirmButton = within(dialog).getByRole('button', { name: '계정 삭제' })
    expect(confirmButton).toBeDisabled()

    await user.type(within(dialog).getByRole('textbox'), 'kim')
    await user.click(confirmButton)

    expect(adminApi.deleteAccount).toHaveBeenCalledWith(21)
    expect(await screen.findByText('kim 계정을 삭제했습니다. (팀장 위임 1개 팀)')).toBeInTheDocument()
    expect(adminApi.accounts).toHaveBeenCalledTimes(2)   // 삭제 뒤 목록을 다시 받음
  })

  it('[ADM-12] 아이디를 입력하지 않고 취소하면 삭제하지 않는다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'kim 계정 삭제' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '취소' }))

    expect(adminApi.deleteAccount).not.toHaveBeenCalled()
  })
})
