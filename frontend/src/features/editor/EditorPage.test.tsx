import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { configApi, fileApi } from '@/api/endpoints'
import type { PublicConfig, TextContent } from '@/api/types'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import EditorPage from './EditorPage'

const doc: TextContent = {
  name: '회의록.md', folderId: 5, content: '처음 내용', versionId: 1, editable: true, updatedAt: '2026-09-27T00:00:00Z',
}

function renderEditor() {
  vi.spyOn(fileApi, 'content').mockResolvedValue(doc)
  vi.spyOn(configApi, 'get').mockResolvedValue({ translationEnabled: false } as PublicConfig)
  const router = createMemoryRouter(
    [
      { path: '/files/:fileId/edit', element: <EditorPage /> },
      { path: '/drive/*', element: <p>드라이브 화면</p> },
    ],
    { initialEntries: ['/files/1/edit'] },
  )
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <ConfirmProvider>
          <RouterProvider router={router} />
        </ConfirmProvider>
      </ToastProvider>
    </QueryClientProvider>,
  )
  return router
}

afterEach(() => vi.restoreAllMocks())

describe('EditorPage — 저장하지 않은 변경 보호', () => {
  it('[BUG-04] 앱 안의 다른 화면으로 이동하려 하면 확인하고, 취소하면 편집 내용이 남는다', async () => {
    const user = userEvent.setup()
    const router = renderEditor()
    await user.type(await screen.findByLabelText('문서 내용'), ' 추가')

    await act(() => router.navigate('/drive'))   // 사이드바 링크를 누른 것과 같은 앱 내 이동

    expect(await screen.findByText('저장하지 않은 변경이 있습니다')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '취소' }))
    expect(router.state.location.pathname).toBe('/files/1/edit')
    expect(screen.getByLabelText('문서 내용')).toHaveValue('처음 내용 추가')
  })

  it('[BUG-04] 확인하면 변경을 버리고 이동한다', async () => {
    const user = userEvent.setup()
    const router = renderEditor()
    await user.type(await screen.findByLabelText('문서 내용'), '!')

    await act(() => router.navigate('/drive'))
    await user.click(await screen.findByRole('button', { name: '저장 안 하고 나가기' }))

    expect(await screen.findByText('드라이브 화면')).toBeInTheDocument()
  })

  it('변경이 없으면 확인 없이 이동한다', async () => {
    const router = renderEditor()
    await screen.findByLabelText('문서 내용')

    await act(() => router.navigate('/drive'))

    expect(await screen.findByText('드라이브 화면')).toBeInTheDocument()
    expect(screen.queryByText('저장하지 않은 변경이 있습니다')).not.toBeInTheDocument()
  })
})
