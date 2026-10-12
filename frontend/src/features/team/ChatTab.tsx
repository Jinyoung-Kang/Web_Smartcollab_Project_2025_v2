import { useEffect, useMemo } from 'react'
import { MessagesSquare } from 'lucide-react'
import type { TeamDetail } from '@/api/types'
import { useMe } from '@/auth/AuthProvider'
import { EmptyState, Spinner } from '@/components/ui/misc'
import { PreviewDialog } from '@/features/drive/dialogs/PreviewDialog'
import { useTeamActivity } from '@/realtime/TeamActivity'
import { ChatComposer } from './ChatComposer'
import { ChatMessageList } from './ChatMessageList'
import { groupChatMessages } from './chatMessages'
import { useChatScroll } from './useChatScroll'
import { useNewMessageAnnouncement } from './useNewMessageAnnouncement'
import { useSharedFilePreview } from './useSharedFilePreview'
import { useTeamChat } from './useTeamChat'

/**
 * 팀 채팅 화면. 데이터·보내기·첨부는 useTeamChat, 스크롤은 useChatScroll, 공유 파일 미리보기는 useSharedFilePreview,
 * 메시지 묶기는 순수 함수 groupChatMessages, 그리기는 ChatMessageList·ChatComposer 가 맡습니다.
 * 최근 30개부터 보여 주고 위로 스크롤하면 이전 메시지를 불러옵니다.
 * 보낸 사람은 서버가 인증 정보로 결정합니다 (v1: 클라이언트가 보낸 sender 를 그대로 신뢰).
 */
export function ChatTab({ teamId, team, active = true }: { teamId: number; team: TeamDetail; active?: boolean }) {
  const me = useMe()
  const { setActiveChat } = useTeamActivity()
  const { chat, messages, send, attach, uploading, clear } = useTeamChat(teamId, team.rootFolderId)
  const { scroller, onScroll, followNewMessages } = useChatScroll({
    lastId: messages.at(-1)?.id,
    pageCount: chat.data?.pages.length,
    canLoadOlder: chat.hasNextPage && !chat.isFetchingNextPage,
    loadOlder: () => void chat.fetchNextPage(),
    visible: active,
    loadFailed: chat.isFetchNextPageError,
  })
  const shared = useSharedFilePreview()
  const rows = useMemo(() => groupChatMessages(messages, me.username), [messages, me.username])
  const announcement = useNewMessageAnnouncement(messages, me.username)

  // 보이는 동안만 이 팀의 새 메시지를 "읽음"으로 칩니다. 좁은 화면에서 닫힌 패널이 보는 중으로 등록해 새 메시지
  // 표시가 뜨지 않았습니다 [FB-06].
  useEffect(() => {
    if (!active) return
    setActiveChat(teamId)
    return () => setActiveChat(null)
  }, [teamId, active, setActiveChat])

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* 이름을 달지 않습니다 — 읽을 내용이 곧 알림이고, '메시지' 이름의 입력란과 겹치지 않게 */}
      <p role="status" className="sr-only">{announcement}</p>
      <div ref={scroller} onScroll={onScroll} className="min-h-0 flex-1 overflow-y-auto px-4 py-3">
        {chat.isPending && <Spinner />}
        {chat.isFetchingNextPage && <Spinner className="mb-2 w-full justify-center" label="이전 메시지 불러오는 중" />}
        {chat.data && messages.length === 0 && (
          <EmptyState icon={MessagesSquare} title="아직 대화가 없습니다" description="첫 메시지를 보내 보세요. 파일도 바로 공유할 수 있어요." />
        )}
        <ChatMessageList rows={rows} openingFileId={shared.opening} onOpenFile={(id) => void shared.open(id)} />
      </div>
      <ChatComposer
        canEdit={team.myPermissions.canEdit}
        canClear={team.myPermissions.leader && messages.length > 0}
        uploading={uploading}
        onSend={(content) => {
          followNewMessages()
          return send({ content })
        }}
        onAttach={(file) => {
          followNewMessages()
          void attach(file)
        }}
        onClear={clear}
      />
      <PreviewDialog file={shared.preview} onClose={shared.close} />
    </div>
  )
}
