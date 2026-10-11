import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { shareApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { Item, ShareLink } from '@/api/types'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import { ShareDialog } from './ShareDialog'

const file = (id: number, name: string): Item => ({
  type: 'file', id, name, size: 10, ownerName: '김하늘', createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z', previewKind: 'TEXT', textEditable: true,
})
const link: ShareLink = {
  id: 7, token: 'abc', path: '/s/abc', passwordProtected: false, downloadCount: 0, createdAt: '2026-09-01T00:00:00Z', active: true,
}

let client: QueryClient
function setup(initial: Item | null) {
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const wrap = (node: ReactNode) => (
    <QueryClientProvider client={client}>
      <ToastProvider>
        <ConfirmProvider>{node}</ConfirmProvider>
      </ToastProvider>
    </QueryClientProvider>
  )
  const view = render(wrap(<ShareDialog file={initial} onClose={vi.fn()} />))
  return { show: (f: Item | null) => view.rerender(wrap(<ShareDialog file={f} onClose={vi.fn()} />)) }
}

afterEach(() => vi.restoreAllMocks())

describe('ShareDialog', () => {
  it('[FB-03] 닫았다가 다른 파일로 열면 앞 파일에 입력하던 비밀번호·횟수가 남지 않는다', async () => {
    vi.spyOn(shareApi, 'list').mockResolvedValue([])
    const user = userEvent.setup()
    const { show } = setup(file(1, 'A.txt'))
    await user.type(screen.getByLabelText('비밀번호 (선택)'), 'secret')
    await user.type(screen.getByLabelText('다운로드 횟수 제한'), '3')

    show(null)
    show(file(2, 'B.txt'))

    expect(screen.getByLabelText('비밀번호 (선택)')).toHaveValue('')
    expect(screen.getByLabelText('다운로드 횟수 제한')).toHaveValue(null)
  })

  it('[FB-11] 링크를 만드는 동안 닫아도, 끝나면 그 파일의 링크 목록을 다시 불러온다', async () => {
    vi.spyOn(shareApi, 'list').mockResolvedValue([])
    let finish: (l: ShareLink) => void = () => {}
    vi.spyOn(shareApi, 'create').mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const user = userEvent.setup()
    const { show } = setup(file(1, 'A.txt'))
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    await user.click(screen.getByRole('button', { name: /링크 만들고 복사/ }))

    show(null)
    await act(async () => finish(link))

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['share-links', 1] })
  })

  it('[FB-11] 링크 해제에 실패하면 알린다', async () => {
    vi.spyOn(shareApi, 'list').mockResolvedValue([link])
    vi.spyOn(shareApi, 'revoke').mockRejectedValue(new ApiError(403, 'FORBIDDEN', '파일을 올린 사람 또는 팀장만 공유 링크를 관리할 수 있습니다.'))
    const user = userEvent.setup()
    setup(file(1, 'A.txt'))
    await user.click(await screen.findByRole('button', { name: '링크 해제' }))
    await user.click(within(await screen.findByRole('dialog', { name: '공유 링크를 해제할까요?' })).getByRole('button', { name: '해제' }))

    expect(await screen.findByText('파일을 올린 사람 또는 팀장만 공유 링크를 관리할 수 있습니다.')).toBeInTheDocument()
  })

  it('[L-8] 링크 해제는 확인을 받는다 — 누르자마자 해제돼 받은 사람들의 주소가 바로 끊겼음', async () => {
    vi.spyOn(shareApi, 'list').mockResolvedValue([link])
    const revoke = vi.spyOn(shareApi, 'revoke').mockResolvedValue(undefined)
    const user = userEvent.setup()
    setup(file(1, 'A.txt'))
    await user.click(await screen.findByRole('button', { name: '링크 해제' }))
    await user.click(within(await screen.findByRole('dialog', { name: '공유 링크를 해제할까요?' })).getByRole('button', { name: '취소' }))

    expect(revoke).not.toHaveBeenCalled()
  })

  it('[L-8] 링크 목록을 불러오지 못하면 알린다 — 이전에는 빈 화면', async () => {
    vi.spyOn(shareApi, 'list').mockRejectedValue(new ApiError(500, 'INTERNAL', '서버 오류'))
    setup(file(1, 'A.txt'))

    expect(await screen.findByRole('alert')).toHaveTextContent('링크 목록을 불러오지 못했습니다')
  })
})
