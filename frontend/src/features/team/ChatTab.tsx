import { Fragment, useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { Download, Eraser, MessagesSquare, Paperclip, SendHorizontal } from 'lucide-react'
import { fileApi } from '@/api/endpoints'
import type { TeamDetail } from '@/api/types'
import { useMe } from '@/auth/AuthProvider'
import { Button, IconButton } from '@/components/ui/Button'
import { Avatar, EmptyState, Spinner } from '@/components/ui/misc'
import { useToast } from '@/components/ui/Toast'
import { PreviewDialog } from '@/features/drive/dialogs/PreviewDialog'
import { useTeamActivity } from '@/realtime/TeamActivity'
import { ItemIcon } from '@/lib/fileIcons'
import { formatBytes, formatDay, formatTime } from '@/lib/format'
import { cn } from '@/lib/cn'
import { groupChatMessages, validateChatMessage } from './chatMessages'
import { useChatScroll } from './useChatScroll'
import { useSharedFilePreview } from './useSharedFilePreview'
import { useTeamChat } from './useTeamChat'

/**
 * 팀 채팅 화면. 데이터·보내기·첨부는 useTeamChat, 스크롤은 useChatScroll, 공유 파일 미리보기는 useSharedFilePreview,
 * 메시지 묶기는 순수 함수 groupChatMessages 가 맡습니다. 최근 30개부터 보여 주고 위로 스크롤하면 이전 메시지를 불러옵니다.
 * 보낸 사람은 서버가 인증 정보로 결정합니다 (v1: 클라이언트가 보낸 sender 를 그대로 신뢰).
 * 공유된 파일은 눌러서 드라이브와 같은 미리보기로 열고, 옆의 아이콘으로 바로 내려받습니다.
 */
export function ChatTab({ teamId, team, active = true }: { teamId: number; team: TeamDetail; active?: boolean }) {
  const me = useMe()
  const toast = useToast()
  const { setActiveChat } = useTeamActivity()
  const { chat, messages, send, attach, uploading, clear } = useTeamChat(teamId, team.rootFolderId)
  const { scroller, onScroll, followNewMessages } = useChatScroll({
    lastId: messages.at(-1)?.id,
    pageCount: chat.data?.pages.length,
    canLoadOlder: chat.hasNextPage && !chat.isFetchingNextPage,
    loadOlder: () => void chat.fetchNextPage(),
  })
  const shared = useSharedFilePreview()
  const rows = useMemo(() => groupChatMessages(messages, me.username), [messages, me.username])
  const [text, setText] = useState('')
  const fileInput = useRef<HTMLInputElement>(null)

  // 보이는 동안만 이 팀의 새 메시지를 "읽음"으로 칩니다. 좁은 화면에서 닫힌 패널이 보는 중으로 등록해 새 메시지
  // 표시가 뜨지 않았습니다 [FB-06].
  useEffect(() => {
    if (!active) return
    setActiveChat(teamId)
    return () => setActiveChat(null)
  }, [teamId, active, setActiveChat])

  const submit = (e?: FormEvent) => {
    e?.preventDefault()
    const checked = validateChatMessage(text)
    if (!checked) return
    if ('error' in checked) return toast.error(checked.error)
    setText('')
    followNewMessages()
    void send({ content: checked.content })
  }

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit()
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div ref={scroller} onScroll={onScroll} className="min-h-0 flex-1 overflow-y-auto px-4 py-3" aria-live="polite">
        {chat.isPending && <Spinner />}
        {chat.isFetchingNextPage && <Spinner className="mb-2 w-full justify-center" label="이전 메시지 불러오는 중" />}
        {chat.data && messages.length === 0 && (
          <EmptyState icon={MessagesSquare} title="아직 대화가 없습니다" description="첫 메시지를 보내 보세요. 파일도 바로 공유할 수 있어요." />
        )}
        {rows.map(({ message: m, mine, newDay, grouped }) => {
          return (
            <Fragment key={m.id}>
              {newDay && (
                <div className="my-4 flex items-center gap-3 text-xs text-slate-500">
                  <span className="h-px flex-1 bg-slate-200" />
                  {formatDay(m.createdAt)}
                  <span className="h-px flex-1 bg-slate-200" />
                </div>
              )}
              <div className={cn('flex gap-2', mine ? 'flex-row-reverse' : '', grouped ? 'mt-1' : 'mt-3')}>
                {!mine && (grouped ? <span className="w-8 shrink-0" /> : <Avatar name={m.sender.name} />)}
                <div className={cn('flex max-w-[78%] flex-col', mine ? 'items-end' : 'items-start')}>
                  {!mine && !grouped && <span className="mb-1 text-xs font-medium text-slate-500">{m.sender.name}</span>}
                  <div className="flex items-end gap-1.5">
                    {mine && <time className="text-[11px] whitespace-nowrap text-slate-500">{formatTime(m.createdAt)}</time>}
                    {m.type === 'FILE_SHARE' && m.file ? (
                      <div className={cn('flex items-center rounded-2xl border text-sm hover:shadow-sm',
                        mine ? 'border-brand-200 bg-brand-50' : 'border-slate-200 bg-white')}>
                        <button
                          type="button"
                          onClick={() => void shared.open(m.file!.id)}
                          aria-label={`${m.file.name} 미리보기`}
                          aria-busy={shared.opening === m.file.id}
                          className={cn('flex min-w-0 items-center gap-3 rounded-l-2xl py-2.5 pr-1 pl-3 text-left',
                            shared.opening === m.file.id && 'cursor-wait opacity-70')}
                        >
                          <ItemIcon type="file" name={m.file.name} className="size-8" />
                          <span className="min-w-0">
                            <span className="block max-w-[12rem] truncate font-medium">{m.file.name}</span>
                            <span className={cn('text-xs', mine ? 'text-slate-600' : 'text-slate-500')}>{formatBytes(m.file.size)}</span>
                          </span>
                        </button>
                        <a
                          href={fileApi.downloadUrl(m.file.id)}
                          download
                          aria-label={`${m.file.name} 내려받기`}
                          title="내려받기"
                          className="mr-1.5 rounded-lg p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-700"
                        >
                          <Download aria-hidden className="size-4" />
                        </a>
                      </div>
                    ) : (
                      <p className={cn('rounded-2xl px-3.5 py-2 text-sm leading-relaxed whitespace-pre-wrap break-words',
                        mine ? 'rounded-br-md bg-brand-600 text-white' : 'rounded-bl-md bg-slate-100 text-slate-800')}>
                        {m.content}
                      </p>
                    )}
                    {!mine && <time className="text-[11px] whitespace-nowrap text-slate-500">{formatTime(m.createdAt)}</time>}
                  </div>
                </div>
              </div>
            </Fragment>
          )
        })}
      </div>

      <form onSubmit={submit} className="border-t border-slate-200 p-3">
        <div className="flex items-end gap-2 rounded-xl border border-slate-300 bg-white p-1.5 focus-within:border-brand-500 focus-within:ring-2 focus-within:ring-brand-100">
          <IconButton label="파일 첨부" onClick={() => fileInput.current?.click()} loading={uploading}
            disabled={!team.myPermissions.canEdit} title={team.myPermissions.canEdit ? '파일 첨부' : '편집 권한이 있어야 파일을 올릴 수 있습니다'}>
            {!uploading && <Paperclip className="size-4" />}
          </IconButton>
          <input ref={fileInput} type="file" hidden onChange={(e) => {
            const f = e.target.files?.[0]
            if (f) {
              followNewMessages()
              void attach(f)
            }
            e.target.value = ''
          }} />
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={onKeyDown}
            rows={1}
            placeholder="메시지 입력 (Shift+Enter 줄바꿈)"
            aria-label="메시지"
            className="max-h-32 min-h-8 flex-1 resize-none bg-transparent px-1 py-1.5 text-sm outline-none"
          />
          <IconButton label="보내기" type="submit" variant="primary" disabled={!text.trim()}>
            <SendHorizontal className="size-4" />
          </IconButton>
        </div>
        {team.myPermissions.leader && messages.length > 0 && (
          <Button size="sm" variant="ghost" className="mt-1 text-slate-500" onClick={clear}>
            <Eraser className="size-3.5" /> 대화 기록 지우기
          </Button>
        )}
      </form>
      <PreviewDialog file={shared.preview} onClose={shared.close} />
    </div>
  )
}
