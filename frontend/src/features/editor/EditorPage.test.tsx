import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { configApi, fileApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { PublicConfig, TextContent } from '@/api/types'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import EditorPage from './EditorPage'

const doc: TextContent = {
  name: '회의록.md', folderId: 5, content: '처음 내용', versionId: 1, editable: true, updatedAt: '2026-09-27T00:00:00Z',
}

let client: QueryClient

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
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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

describe('EditorPage — 다시 불러오기 실패 [FB-02]', () => {
  it('창으로 돌아올 때 하는 새로고침이 실패해도 편집 화면과 저장하지 않은 내용을 그대로 둔다', async () => {
    const user = userEvent.setup()
    renderEditor()
    await user.type(await screen.findByLabelText('문서 내용'), ' 추가')
    vi.mocked(fileApi.content).mockRejectedValue(new ApiError(0, 'NETWORK', '네트워크에 연결할 수 없습니다.'))

    await act(async () => {
      await client.refetchQueries({ queryKey: ['file-content', 1] })
      await new Promise((resolve) => setTimeout(resolve, 0))   // 쿼리 상태 알림은 다음 틱에 화면에 반영됩니다
    })
    expect(client.getQueryState(['file-content', 1])?.status).toBe('error')

    expect(screen.queryByText('문서를 열 수 없습니다')).not.toBeInTheDocument()
    expect(screen.getByLabelText('문서 내용')).toHaveValue('처음 내용 추가')
  })
})

describe('EditorPage — 편집 충돌 해결', () => {
  afterEach(() => {
    Reflect.deleteProperty(navigator, 'clipboard')
    Reflect.deleteProperty(URL, 'createObjectURL')
    Reflect.deleteProperty(URL, 'revokeObjectURL')
  })

  it('[BUG-06] 클립보드를 쓸 수 없으면 내 편집본을 파일로 내려받고, 사실대로 안내한다', async () => {
    const user = userEvent.setup()
    renderEditor()
    vi.spyOn(fileApi, 'save').mockRejectedValue(new ApiError(409, 'EDIT_CONFLICT', '다른 사용자가 먼저 저장했습니다.'))
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockRejectedValue(new DOMException('denied', 'NotAllowedError')) },
    })
    const createUrl = vi.fn(() => 'blob:draft')
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createUrl })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    await user.type(await screen.findByLabelText('문서 내용'), ' 내 수정')
    await user.click(screen.getByRole('button', { name: '저장' }))
    await user.click(await screen.findByRole('button', { name: '최신 내용 불러오기' }))

    expect(await screen.findByText(/파일로 내려받았습니다/)).toBeInTheDocument()
    expect(createUrl).toHaveBeenCalledOnce()
    expect(click).toHaveBeenCalledOnce()
  })

  it('[FB-08] 충돌을 내 내용으로 해결하려는데 최신 내용을 받지 못하면 알리고 충돌 안내를 그대로 둔다', async () => {
    const user = userEvent.setup()
    renderEditor()
    vi.spyOn(fileApi, 'save').mockRejectedValue(new ApiError(409, 'EDIT_CONFLICT', '다른 사용자가 먼저 저장했습니다.'))
    await user.type(await screen.findByLabelText('문서 내용'), ' 내 수정')
    await user.click(screen.getByRole('button', { name: '저장' }))
    vi.mocked(fileApi.content).mockRejectedValue(new ApiError(0, 'NETWORK', '네트워크에 연결할 수 없습니다.'))

    await user.click(await screen.findByRole('button', { name: /내 내용으로/ }))

    expect(await screen.findByText('네트워크에 연결할 수 없습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /내 내용으로/ })).toBeInTheDocument()
  })
})
