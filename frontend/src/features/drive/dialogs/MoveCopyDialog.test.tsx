import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { folderApi } from '@/api/endpoints'
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
})
