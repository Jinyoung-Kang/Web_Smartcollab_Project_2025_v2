import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { chatApi, fileApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { ChatMessage, Item, TeamDetail } from '@/api/types'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import { queryKeys } from '@/api/queryKeys'
import { ChatTab } from './ChatTab'

vi.mock('@/auth/AuthProvider', () => ({
  useMe: () => ({ id: 1, username: 'demo1', name: '김하늘', rootFolderId: 1, createdAt: '2026-09-01T00:00:00Z' }),
  usePublicConfig: () => ({ translationEnabled: false, officePreviewEnabled: false, maxUploadBytes: 1024, demo: { enabled: false, accounts: [] } }),
}))
const upload = vi.hoisted(() => ({ uploadFile: vi.fn() }))
vi.mock('@/api/upload', () => upload)
vi.mock('@/realtime/RealtimeProvider', () => ({ useRealtime: () => ({ publish: () => false }) }))
const activity = vi.hoisted(() => ({ setActiveChat: vi.fn() }))
vi.mock('@/realtime/TeamActivity', () => ({ useTeamActivity: () => activity, appendChatMessage: vi.fn() }))

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

let client: QueryClient

function renderChat(active = true) {
  vi.spyOn(chatApi, 'history').mockResolvedValue({ messages: [shared], hasMore: false })
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter([{ path: '/', element: <ChatTab teamId={3} team={team} active={active} /> }])
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

describe('ChatTab — 보는 중 표시 [FB-06]', () => {
  it('보이지 않는 동안에는 이 팀 채팅을 보는 중으로 등록하지 않는다 (새 메시지 표시가 뜸)', async () => {
    activity.setActiveChat.mockClear()
    renderChat(false)
    await screen.findByRole('button', { name: '화면 스케치.png 미리보기' })
    expect(activity.setActiveChat).not.toHaveBeenCalledWith(3)
  })

  it('보이면 보는 중으로 등록한다', async () => {
    activity.setActiveChat.mockClear()
    renderChat(true)
    await screen.findByRole('button', { name: '화면 스케치.png 미리보기' })
    expect(activity.setActiveChat).toHaveBeenCalledWith(3)
  })
})

describe('ChatTab — 파일 첨부 뒤 다시 불러오기', () => {
  it('[5단계] 채팅으로 올린 파일도 팀 폴더·팀 사용량을 바로 다시 불러온다 (이전: 폴더만)', async () => {
    upload.uploadFile.mockResolvedValue(current)
    vi.spyOn(chatApi, 'send').mockResolvedValue(shared)
    renderChat()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    await screen.findByRole('button', { name: '화면 스케치.png 미리보기' })

    const input = document.querySelector<HTMLInputElement>('input[type=file]')!
    fireEvent.change(input, { target: { files: [new File(['x'], '첨부.png', { type: 'image/png' })] } })

    await vi.waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.usage.of(team.id) }))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.folder.of(team.rootFolderId) })
  })
})

