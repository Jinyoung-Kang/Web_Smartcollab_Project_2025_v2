import { fireEvent, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { teamApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import { TeamPanel } from './TeamPanel'

vi.mock('@/auth/AuthProvider', () => ({
  useMe: () => ({ id: 1, username: 'demo1', name: '김하늘', rootFolderId: 1 }),
}))
vi.mock('@/realtime/TeamActivity', () => ({ useTeamActivity: () => ({ unread: new Set<number>(), setActiveChat: () => {} }) }))
vi.mock('./useTeamPresence', () => ({ useTeamPresence: () => new Set<string>() }))
vi.mock('@/api/endpoints', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/endpoints')>()
  return { ...actual, teamApi: { ...actual.teamApi, detail: vi.fn() } }
})

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastProvider>
          <ConfirmProvider>
            <TeamPanel teamId={3} />
          </ConfirmProvider>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('TeamPanel', () => {
  it('[L-5] 팀 정보를 불러오지 못하면 끝없이 "불러오는 중"을 보이지 않고 오류와 다시 시도를 보인다', async () => {
    vi.mocked(teamApi.detail).mockRejectedValue(new ApiError(0, 'NETWORK', '서버에 연결할 수 없습니다.'))
    renderPanel()

    expect(await screen.findByText(/팀 정보를 불러오지 못했습니다/)).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(teamApi.detail).toHaveBeenCalledTimes(2)
  })
})
