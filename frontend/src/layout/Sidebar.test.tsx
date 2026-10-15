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

describe('Sidebar — 보고 있는 드라이브의 사용량 [UX-08]', () => {
  const GB = 1024 * 1024 * 1024
  const renderAt = (path: string) =>
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <ToastProvider>
          <MemoryRouter initialEntries={[path]}>
            <Sidebar />
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>,
    )

  beforeEach(() => {
    vi.spyOn(teamApi, 'list').mockResolvedValue([
      { id: 5, name: '디자인 팀', ownerName: '김하늘', memberCount: 3, rootFolderId: 50, myPermissions: { canEdit: true, canDelete: true, canInvite: true, leader: true } },
    ])
    vi.spyOn(fileApi, 'usage').mockImplementation(async (teamId?: number) =>
      teamId === 5
        ? { fileCount: 4, totalBytes: GB, storedBytes: GB, quotaBytes: 5 * GB }
        : { fileCount: 1, totalBytes: 1024, storedBytes: 1024, quotaBytes: 2 * GB })
  })

  it('팀 드라이브(폴더·휴지통)에서는 그 팀의 사용량·전체 용량·남은 용량을 보여 준다 — 예전에는 늘 내 드라이브 사용량만 보였음', async () => {
    renderAt('/teams/5/folders/50')
    expect(await screen.findByText('팀 드라이브 사용량')).toBeInTheDocument()
    expect(screen.getByText('디자인 팀', { selector: 'p' })).toBeInTheDocument()
    expect(screen.getByText('1.0 GB / 5.0 GB')).toBeInTheDocument()
    expect(screen.getByText('남은 용량 4.0 GB')).toBeInTheDocument()
    expect(fileApi.usage).toHaveBeenCalledWith(5)
  })

  it('팀 휴지통도 팀 사용량, 그 밖의 화면은 내 드라이브 사용량', async () => {
    renderAt('/teams/5/trash')
    expect(await screen.findByText('남은 용량 4.0 GB')).toBeInTheDocument()

    vi.mocked(fileApi.usage).mockClear()
    renderAt('/drive')
    expect(await screen.findByText('내 드라이브 사용량')).toBeInTheDocument()
    expect(screen.getByText('남은 용량 2.0 GB')).toBeInTheDocument()
    expect(fileApi.usage).toHaveBeenCalledWith(undefined)
  })
})
