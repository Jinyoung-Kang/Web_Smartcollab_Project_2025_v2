import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { chatApi, fileApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { ChatMessage, Item, TeamDetail } from '@/api/types'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import { ChatTab } from './ChatTab'

vi.mock('@/auth/AuthProvider', () => ({
  useMe: () => ({ id: 1, username: 'demo1', name: '김하늘', rootFolderId: 1, createdAt: '2026-09-01T00:00:00Z' }),
  usePublicConfig: () => ({ translationEnabled: false, officePreviewEnabled: false, maxUploadBytes: 1024, demo: { enabled: false, accounts: [] } }),
}))
vi.mock('@/realtime/RealtimeProvider', () => ({ useRealtime: () => ({ publish: () => false }) }))
vi.mock('@/realtime/TeamActivity', () => ({ useTeamActivity: () => ({ setActiveChat: () => {} }), appendChatMessage: vi.fn() }))

const team: TeamDetail = {
  id: 3, name: '데모 팀', ownerUsername: 'demo1', rootFolderId: 10,
  myPermissions: { canEdit: true, canDelete: true, canInvite: true, leader: true }, members: [],
}
const createdAt = new Date().toISOString()
const shared: ChatMessage = {
  id: 5, type: 'FILE_SHARE', content: '', sender: { username: 'demo3', name: '박서연' },
  file: { id: 42, name: '화면 스케치.png', size: 9500 }, createdAt,
}
const current: Item = {
  type: 'file', id: 42, name: '화면 스케치.png', size: 9500, extension: 'png', ownerName: '박서연',
  createdAt, updatedAt: createdAt, previewKind: 'IMAGE', textEditable: false,
}

function renderChat() {
  vi.spyOn(chatApi, 'history').mockResolvedValue({ messages: [shared], hasMore: false })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter([{ path: '/', element: <ChatTab teamId={3} team={team} /> }])
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

describe('ChatTab — 채팅에 공유된 파일 미리보기', () => {
  it('파일을 누르면 지금 파일 정보를 받아 드라이브와 같은 미리보기가 열린다', async () => {
    const user = userEvent.setup()
    renderChat()
    const get = vi.spyOn(fileApi, 'get').mockResolvedValue(current)

    await user.click(await screen.findByRole('button', { name: '화면 스케치.png 미리보기' }))

    expect(get).toHaveBeenCalledWith(42)
    const dialog = await screen.findByRole('dialog', { name: '화면 스케치.png' })
    expect(within(dialog).getByRole('img', { name: '화면 스케치.png' })).toHaveAttribute('src', '/api/files/42/view')
  })

  it('내려받기는 파일 옆의 링크로 따로 할 수 있다', async () => {
    renderChat()
    expect(await screen.findByRole('link', { name: '화면 스케치.png 내려받기' })).toHaveAttribute('href', '/api/files/42/download')
  })

  it('공유한 뒤 파일이 지워졌거나 휴지통에 있으면 미리보기 대신 안내한다', async () => {
    const user = userEvent.setup()
    renderChat()
    vi.spyOn(fileApi, 'get').mockRejectedValue(new ApiError(404, 'NOT_FOUND', '파일을 찾을 수 없습니다.'))

    await user.click(await screen.findByRole('button', { name: '화면 스케치.png 미리보기' }))

    expect(await screen.findByText('파일을 찾을 수 없습니다. 삭제되었거나 휴지통에 있을 수 있습니다.')).toBeInTheDocument()
    expect(document.querySelector('dialog[open]')).toBeNull()
  })
})
