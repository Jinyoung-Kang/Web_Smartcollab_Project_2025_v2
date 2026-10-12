import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { chatApi, fileApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { ChatMessage, Item, TeamDetail } from '@/api/types'
import { ConfirmProvider } from '@/components/ui/Confirm'
import { ToastProvider } from '@/components/ui/Toast'
import { queryKeys } from '@/api/queryKeys'
import { formatBytes } from '@/lib/format'
import { appendChatMessage } from '@/realtime/teamEvents'
import { ChatTab } from './ChatTab'

vi.mock('@/auth/AuthProvider', () => ({
  useMe: () => ({ id: 1, username: 'demo1', name: '김하늘', rootFolderId: 1, createdAt: '2026-09-01T00:00:00Z' }),
  usePublicConfig: () => ({ translationEnabled: false, officePreviewEnabled: false, maxUploadBytes: 1024, demo: { enabled: false, accounts: [] } }),
}))
const upload = vi.hoisted(() => ({ uploadFile: vi.fn() }))
vi.mock('@/api/upload', () => upload)
const realtime = vi.hoisted(() => ({ publish: vi.fn(() => false) }))
vi.mock('@/realtime/RealtimeProvider', () => ({ useRealtime: () => realtime }))
const activity = vi.hoisted(() => ({ setActiveChat: vi.fn() }))
vi.mock('@/realtime/TeamActivity', () => ({ useTeamActivity: () => activity }))

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

function renderChat(active = true, detail: TeamDetail = team) {
  vi.spyOn(chatApi, 'history').mockResolvedValue({ messages: [shared], hasMore: false })
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter([{ path: '/', element: <ChatTab teamId={3} team={detail} active={active} /> }])
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

describe('ChatTab — 첨부 파일 검사', () => {
  it('[5단계] 업로드 한도를 넘는 파일은 올리기 전에 거른다 (드라이브 업로드와 같은 검사)', async () => {
    upload.uploadFile.mockClear()
    renderChat()
    await screen.findByRole('button', { name: '화면 스케치.png 미리보기' })

    const input = document.querySelector<HTMLInputElement>('input[type=file]')!
    fireEvent.change(input, { target: { files: [new File([new Uint8Array(2048)], '큰 파일.bin')] } })

    expect(await screen.findByText(`최대 ${formatBytes(1024)}까지 올릴 수 있습니다.`)).toBeInTheDocument()
    expect(upload.uploadFile).not.toHaveBeenCalled()
  })
})

describe('ChatTab — 메시지 보내기 [M-4]', () => {
  it('보내기가 실패하면 쓴 메시지를 입력란에 되돌린다 — 예전에는 지운 뒤 실패 안내만 떠 메시지가 사라짐', async () => {
    const user = userEvent.setup()
    renderChat()
    vi.spyOn(chatApi, 'send').mockRejectedValue(new ApiError(0, 'NETWORK', '네트워크에 연결할 수 없습니다.'))
    const box = await screen.findByLabelText('메시지')

    await user.type(box, '안녕하세요{Enter}')

    expect(await screen.findByText('네트워크에 연결할 수 없습니다.')).toBeInTheDocument()
    expect(box).toHaveValue('안녕하세요')
  })

  it('웹소켓이 연결돼 있어도 HTTP 로 보내 결과를 확인한다 — 반쯤 끊긴 소켓에 보낸 메시지가 확인 없이 사라졌음', async () => {
    const user = userEvent.setup()
    realtime.publish.mockReturnValue(true)
    renderChat()
    const sent = vi.spyOn(chatApi, 'send').mockResolvedValue({
      id: 99, type: 'CHAT', content: '안녕하세요', sender: { username: 'demo1', name: '김하늘' }, createdAt,
    })

    await user.type(await screen.findByLabelText('메시지'), '안녕하세요{Enter}')

    await vi.waitFor(() => expect(sent).toHaveBeenCalledWith(3, { content: '안녕하세요' }))
  })
})

describe('ChatTab — 화면 읽기 프로그램 알림', () => {
  it('[L-8] 대화 목록 전체를 알리지 않고, 다른 사람이 보낸 새 메시지 하나만 알린다', async () => {
    renderChat()
    await screen.findByRole('button', { name: '화면 스케치.png 미리보기' })
    const live = screen.getAllByRole('status').find((el) => el.tagName === 'P')!
    expect(live).toBeEmptyDOMElement()   // 처음 불러온 기록은 알리지 않음

    await act(async () => appendChatMessage(client, 3, {
      id: 6, type: 'CHAT', content: '회의 자료 올렸어요', sender: { username: 'demo3', name: '박서연' }, createdAt,
    }))

    expect(await screen.findByText('회의 자료 올렸어요')).toBeInTheDocument()   // 캐시 변경은 다음 틱에 화면에 반영됨
    expect(live).toHaveTextContent('박서연: 회의 자료 올렸어요')
    expect(screen.getByRole('button', { name: '화면 스케치.png 미리보기' }).closest('[aria-live]')).toBeNull()
  })
})

describe('ChatTab — 입력부 (리팩터링 전 동작 고정)', () => {
  it('Enter 는 보내고 Shift+Enter 는 줄을 바꾸며, 공백만 있으면 보내지 않는다', async () => {
    const user = userEvent.setup()
    renderChat()
    const sent = vi.spyOn(chatApi, 'send').mockResolvedValue({
      id: 99, type: 'CHAT', content: '첫 줄\n둘째 줄', sender: { username: 'demo1', name: '김하늘' }, createdAt,
    })
    const box = await screen.findByLabelText('메시지')

    await user.type(box, '   {Enter}')
    expect(sent).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled()
    await user.clear(box)
    await user.type(box, '첫 줄{Shift>}{Enter}{/Shift}둘째 줄')
    expect(box).toHaveValue('첫 줄\n둘째 줄')
    await user.keyboard('{Enter}')

    await vi.waitFor(() => expect(sent).toHaveBeenCalledWith(3, { content: '첫 줄\n둘째 줄' }))
    expect(box).toHaveValue('')
  })

  it('대화 기록 지우기는 팀장에게만 보이고, 편집 권한이 없으면 파일 첨부를 막는다', async () => {
    renderChat(true, { ...team, myPermissions: { canEdit: false, canDelete: false, canInvite: false, leader: false } })
    await screen.findByRole('button', { name: '화면 스케치.png 미리보기' })

    expect(screen.queryByRole('button', { name: /대화 기록 지우기/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '파일 첨부' })).toBeDisabled()
  })

  it('팀장이고 메시지가 있으면 대화 기록 지우기가 보인다', async () => {
    renderChat()
    expect(await screen.findByRole('button', { name: /대화 기록 지우기/ })).toBeInTheDocument()
  })
})
