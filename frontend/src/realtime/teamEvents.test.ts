import { QueryClient } from '@tanstack/react-query'
import { CLIENT_ID } from '@/api/clientId'
import { queryKeys } from '@/api/queryKeys'
import type { AppNotification, ChatMessage } from '@/api/types'
import { appendChatMessage, applyNotification, applyReconnect, applyTeamEvent, shouldMarkUnread } from './teamEvents'

function client() {
  const qc = new QueryClient()
  return { qc, invalidate: vi.spyOn(qc, 'invalidateQueries'), remove: vi.spyOn(qc, 'removeQueries'), reset: vi.spyOn(qc, 'resetQueries') }
}

afterEach(() => vi.useRealTimers())

describe('applyTeamEvent', () => {
  it('폴더가 바뀌면 (잠시 모았다가) 그 폴더와 그 팀 스토리지의 트리·사용량·휴지통을 다시 불러온다', () => {
    vi.useFakeTimers()
    const { qc, invalidate } = client()
    expect(applyTeamEvent(qc, 3, { type: 'FOLDER_CHANGED', folderId: 11 })).toBeUndefined()
    vi.advanceTimersByTime(1000)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.folder.of(11) })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.tree.of(3) })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.trash.of(3) })
  })

  it('[IMP-03] 이 탭이 일으킨 폴더 변경이면 다시 불러오지 않는다 (변경 요청의 응답이 이미 갱신함), 다른 탭·사람이면 불러온다', () => {
    vi.useFakeTimers()
    const { qc, invalidate } = client()
    applyTeamEvent(qc, 3, { type: 'FOLDER_CHANGED', folderId: 11, origin: CLIENT_ID, originUserId: 7 }, 7)
    vi.advanceTimersByTime(1000)
    expect(invalidate).not.toHaveBeenCalled()
    applyTeamEvent(qc, 3, { type: 'FOLDER_CHANGED', folderId: 11, origin: 'other-tab-1234', originUserId: 7 }, 7)
    vi.advanceTimersByTime(1000)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.folder.of(11) })
  })

  it('[L-3] 탭 ID 가 같아도 다른 사용자가 일으킨 변경이면 다시 불러온다 (남의 탭 ID 를 보내 갱신을 막지 못하게)', () => {
    vi.useFakeTimers()
    const { qc, invalidate } = client()
    applyTeamEvent(qc, 3, { type: 'FOLDER_CHANGED', folderId: 11, origin: CLIENT_ID, originUserId: 8 }, 7)
    vi.advanceTimersByTime(1000)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.folder.of(11) })
    invalidate.mockClear()
    applyTeamEvent(qc, 3, { type: 'FOLDER_CHANGED', folderId: 12, origin: CLIENT_ID }, 7)
    vi.advanceTimersByTime(1000)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.folder.of(12) })
  })

  it('채팅을 비우면 그 팀 채팅을 처음부터, 팀이 지워지면 팀을 캐시에서 빼고 알린다', () => {
    const { qc, reset, remove } = client()
    applyTeamEvent(qc, 3, { type: 'CHAT_CLEARED', teamId: 3 })
    expect(reset).toHaveBeenCalledWith({ queryKey: queryKeys.chat.of(3) })
    expect(applyTeamEvent(qc, 3, { type: 'TEAM_DELETED', teamId: 3 })).toBe('team-deleted')
    expect(remove).toHaveBeenCalledWith({ queryKey: queryKeys.team(3) })
  })
})

describe('applyReconnect', () => {
  it('[FB-05] 다시 연결되면 팀 목록·채팅·알림을 다시 받는다', () => {
    const { qc, invalidate } = client()
    applyReconnect(qc)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.teams })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.chat.all })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.notifications })
  })

  it('[M-8] 끊긴 동안 놓친 폴더 변경·권한 변경도 다시 받는다 (서버 재시작·프록시 끊김에는 브라우저 online 이벤트가 없음)', () => {
    const { qc, invalidate } = client()
    applyReconnect(qc)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.folder.all })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.tree.all })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['team'] })
  })
})

describe('applyTeamEvent — 멤버 변경 [M-8]', () => {
  it('내 권한이 바뀌었을 수 있으므로 폴더 응답(권한을 담음)도 다시 받는다 — 드라이브 단추와 채팅 권한이 어긋나지 않게', () => {
    const { qc, invalidate } = client()
    applyTeamEvent(qc, 3, { type: 'MEMBERS_CHANGED', teamId: 3 })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.team(3) })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.folder.all })
  })
})

describe('shouldMarkUnread', () => {
  const msg = { sender: { username: 'demo2', name: '이도윤' } } as ChatMessage
  it('남이 보냈고 그 팀 채팅을 보고 있지 않을 때만', () => {
    expect(shouldMarkUnread(msg, 'demo1', null, 3)).toBe(true)
    expect(shouldMarkUnread(msg, 'demo1', 3, 3)).toBe(false)
    expect(shouldMarkUnread(msg, 'demo2', null, 3)).toBe(false)
  })
})

describe('applyNotification', () => {
  const n = (type: AppNotification['type'], teamId?: number) => ({ id: 1, type, content: '', read: false, createdAt: '', teamId }) as AppNotification
  it('팀에서 제외되면 그 팀을 캐시에서 빼고 알린다, 초대는 팀 목록을 건드리지 않는다', () => {
    const { qc, invalidate, remove } = client()
    expect(applyNotification(qc, n('REMOVED_FROM_TEAM', 3))).toEqual({ removedFromTeam: 3 })
    expect(remove).toHaveBeenCalledWith({ queryKey: queryKeys.team(3) })
    invalidate.mockClear()
    expect(applyNotification(qc, n('TEAM_INVITE', 4))).toEqual({})
    expect(invalidate).toHaveBeenCalledTimes(1)   // 알림 목록만
  })
})

describe('appendChatMessage', () => {
  const msg = (id: number) => ({ id, type: 'CHAT', content: `${id}`, sender: { username: 'a', name: '가' }, createdAt: '2026-10-01T00:00:00Z' }) as ChatMessage

  it('불러온 채팅의 최신 묶음 끝에 붙이고, 이미 있는 메시지(보낸 응답과 방송이 둘 다 옴)는 다시 붙이지 않는다', () => {
    const qc = new QueryClient()
    qc.setQueryData(queryKeys.chat.of(3), { pages: [{ messages: [msg(1)], hasMore: false }], pageParams: [undefined] })
    appendChatMessage(qc, 3, msg(2))
    appendChatMessage(qc, 3, msg(2))
    expect(qc.getQueryData<{ pages: { messages: ChatMessage[] }[] }>(queryKeys.chat.of(3))?.pages[0]?.messages.map((m) => m.id)).toEqual([1, 2])
  })

  it('아직 불러오지 않은 채팅은 만들지 않는다 (열 때 서버에서 받음)', () => {
    const qc = new QueryClient()
    appendChatMessage(qc, 3, msg(2))
    expect(qc.getQueryData(queryKeys.chat.of(3))).toBeUndefined()
  })
})
