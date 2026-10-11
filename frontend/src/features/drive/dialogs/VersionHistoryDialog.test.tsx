import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { fileApi } from '@/api/endpoints'
import { queryKeys } from '@/api/queryKeys'
import type { Item, Permissions } from '@/api/types'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import { VersionHistoryDialog } from './VersionHistoryDialog'

const file: Item = {
  type: 'file', id: 1, name: '계약.txt', size: 10, ownerName: '김하늘', createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z', previewKind: 'TEXT', textEditable: true,
}
const permissions: Permissions = { canEdit: true, canDelete: true, canInvite: true, leader: true }

afterEach(() => vi.restoreAllMocks())

describe('VersionHistoryDialog', () => {
  it('[FB-11] 서명하는 동안 닫아도, 끝나면 그 파일의 버전 기록·내용을 다시 불러온다', async () => {
    vi.spyOn(fileApi, 'versions').mockResolvedValue([])
    let finish: () => void = () => {}
    vi.spyOn(fileApi, 'sign').mockReturnValue(new Promise<void>((resolve) => { finish = resolve }))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const wrap = (node: ReactNode) => (
      <QueryClientProvider client={client}>
        <ToastProvider>
          <ConfirmProvider>{node}</ConfirmProvider>
        </ToastProvider>
      </QueryClientProvider>
    )
    const view = render(wrap(<VersionHistoryDialog file={file} permissions={permissions} onClose={vi.fn()} />))
    await userEvent.setup().click(screen.getByRole('button', { name: /현재 버전에 서명/ }))

    view.rerender(wrap(<VersionHistoryDialog file={null} permissions={permissions} onClose={vi.fn()} />))
    await act(async () => finish())

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['versions', 1] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['file-content', 1] })
  })

  it('[L-8] 버전 기록을 불러오지 못하면 알린다 — 이전에는 빈 목록', async () => {
    vi.spyOn(fileApi, 'versions').mockRejectedValue(new Error('서버 오류'))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <ToastProvider>
          <ConfirmProvider>
            <VersionHistoryDialog file={file} permissions={permissions} onClose={vi.fn()} />
          </ConfirmProvider>
        </ToastProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('버전 기록을 불러오지 못했습니다')
  })

  it('동작 고정: 옛 버전을 되돌리기는 확인을 받고, 그 파일의 버전 기록·내용·폴더 목록을 다시 불러온다', async () => {
    vi.spyOn(fileApi, 'versions').mockResolvedValue([
      { versionId: 2, createdAt: '2026-09-02T00:00:00Z', editorName: '김하늘', size: 10, sha256: 'b'.repeat(64), active: true, signatures: [] },
      { versionId: 1, createdAt: '2026-09-01T00:00:00Z', editorName: '김하늘', size: 8, sha256: 'a'.repeat(64), active: false, signatures: [] },
    ])
    const restore = vi.spyOn(fileApi, 'restore').mockResolvedValue(undefined)
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    render(
      <QueryClientProvider client={client}>
        <ToastProvider>
          <ConfirmProvider>
            <VersionHistoryDialog file={file} permissions={permissions} onClose={vi.fn()} />
          </ConfirmProvider>
        </ToastProvider>
      </QueryClientProvider>,
    )
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /되돌리기/ }))
    await user.click(within(await screen.findByRole('dialog', { name: '이 버전으로 되돌릴까요?' })).getByRole('button', { name: '되돌리기' }))

    await vi.waitFor(() => expect(restore).toHaveBeenCalledWith(1, 1))
    await vi.waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['versions', 1] }))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['file-content', 1] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.folder.all })
  })
})
