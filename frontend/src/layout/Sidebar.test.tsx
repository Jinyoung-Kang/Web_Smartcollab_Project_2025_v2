import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { fileApi, teamApi } from '@/api/endpoints'
import { ToastProvider } from '@/components/ui/Toast'
import { Sidebar } from './Sidebar'

vi.mock('@/realtime/TeamActivity', () => ({ useTeamActivity: () => ({ unread: new Set([5]), setActiveChat: vi.fn() }) }))

afterEach(() => vi.restoreAllMocks())

describe('Sidebar — 안 읽은 채팅 표시 [M-7]', () => {
  it('안 읽은 채팅이 있는 팀은 화면 읽기 프로그램이 읽을 수 있는 표시를 단다 — 역할 없는 span 의 aria-label 은 읽히지 않음', async () => {
    vi.spyOn(teamApi, 'list').mockResolvedValue([
      { id: 5, name: '디자인 팀', ownerName: '김하늘', memberCount: 3, rootFolderId: 50, myPermissions: { canEdit: true, canDelete: true, canInvite: true, leader: true } },
    ])
    vi.spyOn(fileApi, 'usage').mockResolvedValue({ usedBytes: 0, limitBytes: 1024, fileCount: 0 } as never)
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <ToastProvider>
          <MemoryRouter>
            <Sidebar />
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('img', { name: '새 채팅 메시지' })).toBeInTheDocument()
  })
})
