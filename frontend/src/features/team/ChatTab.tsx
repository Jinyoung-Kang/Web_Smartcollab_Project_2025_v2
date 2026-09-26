import { Fragment, useEffect, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { Download, Eraser, MessagesSquare, Paperclip, SendHorizontal } from 'lucide-react'
import { chatApi, fileApi } from '@/api/endpoints'
import { uploadFile } from '@/api/upload'
import type { ChatMessage, TeamDetail } from '@/api/types'
import { useMe } from '@/auth/AuthProvider'
import { Button, IconButton } from '@/components/ui/Button'
import { useConfirm } from '@/components/ui/Confirm'
import { Avatar, EmptyState, Spinner } from '@/components/ui/misc'
import { useToast } from '@/components/ui/Toast'
import { useRealtime } from '@/realtime/RealtimeProvider'
import { appendChatMessage, useTeamActivity } from '@/realtime/TeamActivity'
import { ItemIcon } from '@/lib/fileIcons'
import { formatBytes, formatDay, formatTime, sameDay } from '@/lib/format'
import { cn } from '@/lib/cn'

/**
 * 팀 채팅. 최근 30개부터 보여 주고 위로 스크롤하면 이전 메시지를 커서 기반으로 더 불러옵니다.
 * 보낸 사람은 서버가 인증 정보로 결정합니다 (v1: 클라이언트가 보낸 sender 를 그대로 신뢰).
 */
export function ChatTab({ teamId, team }: { teamId: number; team: TeamDetail }) {
  const me = useMe()
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const { publish } = useRealtime()
  const { setActiveChat } = useTeamActivity()
  const [text, setText] = useState('')
  const [uploading, setUploading] = useState(false)
  const scroller = useRef<HTMLDivElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const stickToBottom = useRef(true)
  const prevHeight = useRef(0)

  const chat = useInfiniteQuery({
    queryKey: ['chat', teamId],
    queryFn: ({ pageParam }) => chatApi.history(teamId, pageParam),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => (last.hasMore ? last.messages[0]?.id : undefined),
    staleTime: Infinity,
  })

  useEffect(() => {
    setActiveChat(teamId)
    return () => setActiveChat(null)
  }, [teamId, setActiveChat])

  const messages: ChatMessage[] = chat.data ? [...chat.data.pages].reverse().flatMap((p) => p.messages) : []
  const lastId = messages.at(-1)?.id

  // 새 메시지가 오면 맨 아래에 있을 때만 따라 내려갑니다. 이전 메시지를 불러오면 보던 위치를 유지합니다.
  useLayoutEffect(() => {
    const el = scroller.current
    if (!el) return
    if (stickToBottom.current) {
      el.scrollTop = el.scrollHeight
    } else if (prevHeight.current && el.scrollHeight > prevHeight.current && el.scrollTop < 50) {
      el.scrollTop = el.scrollHeight - prevHeight.current
    }
    prevHeight.current = el.scrollHeight
  }, [lastId, chat.data?.pages.length])

  const onScroll = () => {
    const el = scroller.current
    if (!el) return
    stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
    if (el.scrollTop < 40 && chat.hasNextPage && !chat.isFetchingNextPage) {
      prevHeight.current = el.scrollHeight
      void chat.fetchNextPage()
    }
  }

  const send = async (body: { content?: string; fileId?: number }) => {
    stickToBottom.current = true
    // WebSocket 이 연결돼 있으면 STOMP 로, 아니면 HTTP 로 보냅니다 (결과는 둘 다 구독자에게 방송됨).
    if (publish(`/app/teams/${teamId}/chat`, body)) return
    try {
      const msg = await chatApi.send(teamId, body)
      appendChatMessage(qc, teamId, msg)
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  const submit = (e?: FormEvent) => {
    e?.preventDefault()
    const content = text.trim()
    if (!content) return
    if (content.length > 2000) return toast.error('메시지는 2000자 이하로 입력하세요.')
    setText('')
    void send({ content })
  }

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit()
    }
  }

  const attach = async (file: File) => {
    setUploading(true)
    try {
      const item = await uploadFile(team.rootFolderId, file, () => {})
      void qc.invalidateQueries({ queryKey: ['folder', team.rootFolderId] })
      await send({ fileId: item.id })
    } catch (e) {
      toast.error((e as Error).message)
    } finally {
      setUploading(false)
    }
  }

  const clear = async () => {
    const ok = await confirm({ title: '채팅 기록을 모두 지울까요?', message: '모든 멤버의 화면에서 대화가 사라지며 되돌릴 수 없습니다.', confirmLabel: '모두 지우기', danger: true })
    if (!ok) return
    try {
      await chatApi.clear(teamId)
    } catch (e) {
      toast.error((e as Error).message)
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
        {messages.map((m, i) => {
          const prev = messages[i - 1]
          const mine = m.sender.username === me.username
          const newDay = !prev || !sameDay(prev.createdAt, m.createdAt)
          const grouped = !newDay && prev && prev.sender.username === m.sender.username &&
            new Date(m.createdAt).getTime() - new Date(prev.createdAt).getTime() < 5 * 60_000
          return (
            <Fragment key={m.id}>
              {newDay && (
                <div className="my-4 flex items-center gap-3 text-xs text-slate-400">
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
                    {mine && <time className="text-[11px] text-slate-400">{formatTime(m.createdAt)}</time>}
                    {m.type === 'FILE_SHARE' && m.file ? (
                      <a
                        href={fileApi.downloadUrl(m.file.id)}
                        download
                        className={cn('flex items-center gap-3 rounded-2xl border px-3 py-2.5 text-sm hover:shadow-sm',
                          mine ? 'border-brand-200 bg-brand-50' : 'border-slate-200 bg-white')}
                      >
                        <ItemIcon type="file" name={m.file.name} className="size-8" />
                        <span className="min-w-0">
                          <span className="block max-w-[12rem] truncate font-medium">{m.file.name}</span>
                          <span className="text-xs text-slate-500">{formatBytes(m.file.size)}</span>
                        </span>
                        <Download aria-hidden className="size-4 text-slate-400" />
                      </a>
                    ) : (
                      <p className={cn('rounded-2xl px-3.5 py-2 text-sm leading-relaxed whitespace-pre-wrap break-words',
                        mine ? 'rounded-br-md bg-brand-600 text-white' : 'rounded-bl-md bg-slate-100 text-slate-800')}>
                        {m.content}
                      </p>
                    )}
                    {!mine && <time className="text-[11px] text-slate-400">{formatTime(m.createdAt)}</time>}
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
            if (f) void attach(f)
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
    </div>
  )
}
