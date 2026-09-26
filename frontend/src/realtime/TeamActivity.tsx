import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient, type InfiniteData, type QueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate } from 'react-router'
import { teamApi } from '@/api/endpoints'
import type { AppNotification, ChatMessage, ChatPage } from '@/api/types'
import { useMe } from '@/auth/AuthProvider'
import { useToast } from '@/components/ui/Toast'
import { useRealtime, useSubscription } from './RealtimeProvider'

interface TeamActivityApi {
  unread: ReadonlySet<number>
  /** 채팅 탭이 열려 있는 팀 (그 팀의 새 메시지는 안 읽음으로 표시하지 않음) */
  setActiveChat: (teamId: number | null) => void
}

const Ctx = createContext<TeamActivityApi | null>(null)

type TeamEvent =
  | { type: 'FOLDER_CHANGED'; folderId: number }
  | { type: 'MEMBERS_CHANGED' | 'TEAM_DELETED' | 'CHAT_CLEARED'; teamId: number }

export function appendChatMessage(qc: QueryClient, teamId: number, msg: ChatMessage) {
  qc.setQueryData<InfiniteData<ChatPage, number | undefined>>(['chat', teamId], (data) => {
    if (!data || data.pages.length === 0) return data
    const [latest, ...older] = data.pages
    if (!latest || latest.messages.some((m) => m.id === msg.id)) return data
    return { ...data, pages: [{ ...latest, messages: [...latest.messages, msg] }, ...older] }
  })
}

/**
 * 내 모든 팀의 채팅·이벤트 토픽을 구독해, 다른 사람의 변경을 즉시 화면에 반영합니다.
 * (서버는 트랜잭션 커밋 후에만 이벤트를 보내므로, 받은 시점에 다시 조회하면 항상 최신 상태입니다.)
 */
export function TeamActivityProvider({ children }: { children: ReactNode }) {
  const me = useMe()
  const qc = useQueryClient()
  const toast = useToast()
  const navigate = useNavigate()
  const location = useLocation()
  const { subscribe } = useRealtime()
  const teams = useQuery({ queryKey: ['teams'], queryFn: teamApi.list })
  const [unread, setUnread] = useState<Set<number>>(new Set())
  const activeChat = useRef<number | null>(null)
  const pathRef = useRef(location.pathname)
  useEffect(() => {
    pathRef.current = location.pathname
  }, [location.pathname])

  const teamIds = useMemo(() => (teams.data ?? []).map((t) => t.id).sort((a, b) => a - b).join(','), [teams.data])

  useEffect(() => {
    if (!teamIds) return
    const ids = teamIds.split(',').map(Number)
    const unsubs = ids.flatMap((teamId) => [
      subscribe(`/topic/teams/${teamId}/chat`, (payload) => {
        const msg = payload as ChatMessage
        appendChatMessage(qc, teamId, msg)
        if (msg.sender.username !== me.username && activeChat.current !== teamId) {
          setUnread((prev) => new Set(prev).add(teamId))
        }
      }),
      subscribe(`/topic/teams/${teamId}/events`, (payload) => {
        const event = payload as TeamEvent
        switch (event.type) {
          case 'FOLDER_CHANGED':
            void qc.invalidateQueries({ queryKey: ['folder', event.folderId] })
            void qc.invalidateQueries({ queryKey: ['tree', teamId] })
            void qc.invalidateQueries({ queryKey: ['usage', teamId] })
            void qc.invalidateQueries({ queryKey: ['trash', teamId] })
            void qc.invalidateQueries({ queryKey: ['versions'] })
            break
          case 'MEMBERS_CHANGED':
            void qc.invalidateQueries({ queryKey: ['team', teamId] })
            void qc.invalidateQueries({ queryKey: ['teams'] })
            break
          case 'CHAT_CLEARED':
            void qc.resetQueries({ queryKey: ['chat', teamId] })
            break
          case 'TEAM_DELETED':
            void qc.invalidateQueries({ queryKey: ['teams'] })
            qc.removeQueries({ queryKey: ['team', teamId] })
            if (pathRef.current.startsWith(`/teams/${teamId}`)) {
              toast.info('보고 있던 팀이 삭제되었습니다.')
              navigate('/drive', { replace: true })
            }
            break
        }
      }),
    ])
    return () => unsubs.forEach((u) => u())
  }, [teamIds, subscribe, qc, me.username, toast, navigate])

  useSubscription('/user/queue/notifications', (payload) => {
    const n = payload as AppNotification
    void qc.invalidateQueries({ queryKey: ['notifications'] })
    if (n.type !== 'TEAM_INVITE') {
      void qc.invalidateQueries({ queryKey: ['teams'] })
      if (n.teamId) void qc.invalidateQueries({ queryKey: ['team', n.teamId] })
    }
    toast.info(n.content)
  })

  useSubscription('/user/queue/errors', (payload) => {
    toast.error((payload as { message?: string }).message ?? '요청을 처리하지 못했습니다.')
  })

  const setActiveChat = useCallback((teamId: number | null) => {
    activeChat.current = teamId
    if (teamId !== null) {
      setUnread((prev) => {
        if (!prev.has(teamId)) return prev
        const next = new Set(prev)
        next.delete(teamId)
        return next
      })
    }
  }, [])

  const api = useMemo(() => ({ unread, setActiveChat }), [unread, setActiveChat])
  return <Ctx.Provider value={api}>{children}</Ctx.Provider>
}

export function useTeamActivity(): TeamActivityApi {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('useTeamActivity must be used within TeamActivityProvider')
  return ctx
}
