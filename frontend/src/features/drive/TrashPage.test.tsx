import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { trashApi } from '@/api/endpoints'
import type { TrashItem } from '@/api/types'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import TrashPage from './TrashPage'

const deletedAt = new Date().toISOString()
const folder: TrashItem = {
  type: 'folder', id: 7, name: '보관함', size: 2048, deletedAt, deletedByName: '김하늘', folderId: 1, fileCount: 2,
}
const file: TrashItem = {
  type: 'file', id: 7, name: '메모.txt', size: 10, extension: 'txt', deletedAt, deletedByName: '김하늘', folderId: 1,
}

function renderTrash() {
  vi.spyOn(trashApi, 'list').mockResolvedValue([folder, file])
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter([{ path: '/trash', element: <TrashPage /> }], { initialEntries: ['/trash'] })
  render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <ConfirmProvider>
          <RouterProvider router={router} />
        </ConfirmProvider>
      </ToastProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.restoreAllMocks())

describe('TrashPage — 폴더 휴지통 [UX-06]', () => {
  it('폴더는 안에 든 파일 수와 함께 보이고, 같은 번호의 파일과 섞이지 않는다', async () => {
    renderTrash()
    expect(await screen.findByText('보관함')).toBeInTheDocument()
    expect(screen.getByText('메모.txt')).toBeInTheDocument()
    expect(screen.getByText(/폴더 · 파일 2개/)).toBeInTheDocument()
  })

  it('원래 상위 폴더가 휴지통에 있으면 최상위 폴더로 복원했다고 알린다', async () => {
    const user = userEvent.setup()
    renderTrash()
    const restoreFolder = vi.spyOn(trashApi, 'restoreFolder').mockResolvedValue({ folderId: 1, relocated: true })
    const restoreFile = vi.spyOn(trashApi, 'restore').mockResolvedValue(undefined)

    await user.click((await screen.findAllByRole('button', { name: '복원' }))[0]!)

    expect(restoreFolder).toHaveBeenCalledWith(7)
    expect(restoreFile).not.toHaveBeenCalled()
    expect(await screen.findByText(/최상위 폴더로 복원했습니다/)).toBeInTheDocument()
  })

  it('폴더를 영구 삭제할 때는 안의 파일도 함께 지워진다고 확인한다', async () => {
    const user = userEvent.setup()
    renderTrash()
    const purgeFolder = vi.spyOn(trashApi, 'purgeFolder').mockResolvedValue(undefined)

    await user.click((await screen.findAllByRole('button', { name: '영구 삭제' }))[0]!)
    expect(await screen.findByText(/안의 파일 2개까지 모두 삭제되며/)).toBeInTheDocument()
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '영구 삭제' }))

    expect(purgeFolder).toHaveBeenCalledWith(7)
  })
})
