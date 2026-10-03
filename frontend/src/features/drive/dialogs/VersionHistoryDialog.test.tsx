import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { fileApi } from '@/api/endpoints'
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
})
