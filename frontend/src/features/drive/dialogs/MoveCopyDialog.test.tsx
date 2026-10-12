import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { folderApi, teamApi } from '@/api/endpoints'
import type { TeamSummary } from '@/api/types'
import { MoveCopyDialog } from './MoveCopyDialog'

afterEach(() => vi.restoreAllMocks())

describe('MoveCopyDialog', () => {
  it('[FB-04] 다시 열면 이전에 고른 대상 폴더가 남지 않는다 (다른 스토리지의 폴더로 확인할 수 없음)', async () => {
    vi.spyOn(folderApi, 'tree').mockImplementation(async (teamId?: number) => ({
      roots: [{ id: teamId ? 900 : 100, name: teamId ? '팀 스토리지' : '내 드라이브', children: [{ id: teamId ? 901 : 101, name: teamId ? '팀 자료' : '내 자료', children: [] }] }],
    }))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrap = (node: ReactNode) => <QueryClientProvider client={client}>{node}</QueryClientProvider>
    const props = { mode: 'move' as const, count: 1, disabledFolderIds: [], onClose: vi.fn(), onConfirm: vi.fn() }
    const view = render(wrap(<MoveCopyDialog open {...props} />))
    await userEvent.setup().click(await screen.findByRole('button', { name: '내 자료' }))
    expect(screen.getByRole('button', { name: '여기로 이동' })).toBeEnabled()

    view.rerender(wrap(<MoveCopyDialog open={false} {...props} />))
    view.rerender(wrap(<MoveCopyDialog open {...props} scopeTeamId={5} />))

    expect(await screen.findByRole('button', { name: '팀 자료' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '여기로 이동' })).toBeDisabled()
  })

  it('[L-8] 폴더 목록을 불러오지 못하면 알린다 — 이전에는 고를 폴더 없이 빈 창', async () => {
    vi.spyOn(folderApi, 'tree').mockRejectedValue(new Error('서버 오류'))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <MoveCopyDialog open mode="copy" count={1} disabledFolderIds={[]} onClose={vi.fn()} onConfirm={vi.fn()} />
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('폴더 목록을 불러오지 못했습니다')
  })

  it('[L-5] 고른 스토리지·대상 폴더를 눌린 상태(aria-pressed)로 알린다 — 색으로만 표시해 화면 읽기 프로그램은 무엇을 골랐는지 몰랐음', async () => {
    vi.spyOn(folderApi, 'tree').mockResolvedValue({ roots: [{ id: 100, name: '내 드라이브', children: [{ id: 101, name: '내 자료', children: [] }] }] })
    vi.spyOn(teamApi, 'list').mockResolvedValue([{ id: 5, name: '디자인 팀', myPermissions: { canEdit: true } } as unknown as TeamSummary])
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <MoveCopyDialog open mode="copy" count={1} disabledFolderIds={[]} onClose={vi.fn()} onConfirm={vi.fn()} />
      </QueryClientProvider>,
    )
    const user = userEvent.setup()

    expect(await screen.findByRole('button', { name: '내 드라이브', pressed: true })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '디자인 팀', pressed: false })).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: '내 자료' }))
    expect(screen.getByRole('button', { name: '내 자료' })).toHaveAttribute('aria-pressed', 'true')
  })
})
