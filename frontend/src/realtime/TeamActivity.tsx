import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient, type InfiniteData, type QueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate } from 'react-router'
import { teamApi } from '@/api/endpoints'
import type { AppNotification, ChatMessage, ChatPage } from '@/api/types'
import { useMe } from '@/auth/AuthProvider'
import { useToast } from '@/components/ui/Toast'
import { useOnReconnect } from '@/lib/useOnReconnect'
import { useRealtime, useSubscription } from './RealtimeProvider'
import { queryKeys } from '@/api/queryKeys'
import { applyNotification, applyTeamEvent, shouldMarkUnread, type TeamEvent } from './teamEvents'

interface TeamActivityApi {
  unread: ReadonlySet<number>
  /** 채팅 탭이 열려 있는 팀 (그 팀의 새 메시지는 안 읽음으로 표시하지 않음) */
  setActiveChat: (teamId: number | null) => void
}

const Ctx = createContext<TeamActivityApi | null>(null)

/** 현재 주소가 그 팀의 화면인지 (/teams/1 은 /teams/12 와 구분) */
export function isTeamPath(pathname: string, teamId: number): boolean {
  return pathname === `/teams/${teamId}` || pathname.startsWith(`/teams/${teamId}/`)
}

export function appendChatMessage(qc: QueryClient, teamId: number, msg: ChatMessage) {
  qc.setQueryData<InfiniteData<ChatPage, number | undefined>>(queryKeys.chat.of(teamId), (data) => {
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
  const { subscribe, connected } = useRealtime()
  const teams = useQuery({ queryKey: queryKeys.teams, queryFn: teamApi.list })
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
        if (shouldMarkUnread(msg, me.username, activeChat.current, teamId)) {
          setUnread((prev) => new Set(prev).add(teamId))
        }
      }),
      subscribe(`/topic/teams/${teamId}/events`, (payload) => {
        if (applyTeamEvent(qc, teamId, payload as TeamEvent) === 'team-deleted' && isTeamPath(pathRef.current, teamId)) {
          toast.info('보고 있던 팀이 삭제되었습니다.')
          navigate('/drive', { replace: true })
        }
      }),
    ])
    return () => unsubs.forEach((u) => u())
  }, [teamIds, subscribe, qc, me.username, toast, navigate])

  // 끊긴 동안 팀에서 제외됐을 수 있으므로 재연결하면 팀 목록을 다시 받아 구독을 맞춥니다.
  // (제외된 팀 토픽을 다시 구독하면 서버가 거절하며 연결을 닫습니다)
  // 채팅·알림은 실시간으로만 갱신되므로(채팅 캐시는 만료 없음) 끊긴 동안 온 것을 다시 받습니다 [FB-05].
  useOnReconnect(connected, () => {
    void qc.invalidateQueries({ queryKey: queryKeys.teams })
    void qc.invalidateQueries({ queryKey: queryKeys.chat.all })
    void qc.invalidateQueries({ queryKey: queryKeys.notifications })
  })

  useSubscription('/user/queue/notifications', (payload) => {
    const n = payload as AppNotification
    const { removedFromTeam } = applyNotification(qc, n)
    if (removedFromTeam && isTeamPath(pathRef.current, removedFromTeam)) navigate('/drive', { replace: true })
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
