import { useQuery, useQueryClient } from '@tanstack/react-query'
import { teamApi } from '@/api/endpoints'
import { queryKeys } from '@/api/queryKeys'
import { useSubscription } from '@/realtime/RealtimeProvider'

/**
 * 지금 접속 중인 팀원(아이디). 처음에는 조회하고 이후로는 WebSocket 으로 갱신합니다(구독 자체가 "접속 중" 표시).
 * 이 화면을 보는 나는 항상 접속 중으로 칩니다 — 내 구독이 서버에 등록되기 전에 조회가 끝나는 경쟁 조건 보정.
 */
export function useTeamPresence(teamId: number, myUsername: string): ReadonlySet<string> {
  const qc = useQueryClient()
  const presence = useQuery({ queryKey: queryKeys.presence(teamId), queryFn: () => teamApi.presence(teamId) })
  useSubscription(`/topic/teams/${teamId}/presence`, (payload) => {
    qc.setQueryData(queryKeys.presence(teamId), payload)
  })
  return new Set([...(presence.data?.online ?? []), myUsername])
}
