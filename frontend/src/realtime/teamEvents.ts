import type { QueryClient } from '@tanstack/react-query'
import { invalidateDriveChange } from '@/api/driveCache'
import { queryKeys } from '@/api/queryKeys'
import type { AppNotification, ChatMessage } from '@/api/types'

/** 팀 토픽(/topic/teams/{id}/events)으로 오는 변경 알림. 데이터 대신 "무엇이 바뀌었는지"만 옵니다. */
export type TeamEvent =
  | { type: 'FOLDER_CHANGED'; folderId: number }
  | { type: 'MEMBERS_CHANGED' | 'TEAM_DELETED' | 'CHAT_CLEARED'; teamId: number }

/**
 * 팀 변경 알림을 캐시에 반영합니다. React 없이 테스트할 수 있도록 화면 이동은 하지 않고, 팀이 지워졌으면
 * 'team-deleted' 를 돌려줘 호출하는 쪽이 보고 있던 화면에서 나가게 합니다.
 */
export function applyTeamEvent(qc: QueryClient, teamId: number, event: TeamEvent): 'team-deleted' | undefined {
  switch (event.type) {
    case 'FOLDER_CHANGED':
      invalidateDriveChange(qc, { folderIds: [event.folderId], scope: teamId })
      return undefined
    case 'MEMBERS_CHANGED':
      void qc.invalidateQueries({ queryKey: queryKeys.team(teamId) })
      void qc.invalidateQueries({ queryKey: queryKeys.teams })
      return undefined
    case 'CHAT_CLEARED':
      void qc.resetQueries({ queryKey: queryKeys.chat.of(teamId) })
      return undefined
    case 'TEAM_DELETED':
      void qc.invalidateQueries({ queryKey: queryKeys.teams })
      qc.removeQueries({ queryKey: queryKeys.team(teamId) })
      return 'team-deleted'
  }
}

/** 새 채팅 메시지를 안 읽음으로 표시할지: 남이 보냈고, 그 팀 채팅을 보고 있지 않을 때 */
export function shouldMarkUnread(msg: ChatMessage, myUsername: string, viewingTeamId: number | null, teamId: number): boolean {
  return msg.sender.username !== myUsername && viewingTeamId !== teamId
}

/**
 * 개인 알림을 캐시에 반영합니다. 팀에서 제외됐으면 그 팀 ID 를 돌려줘 호출하는 쪽이 보고 있던 팀 화면에서 나가게 합니다
 * (서버는 이미 그 팀의 실시간 구독을 해제했습니다).
 */
export function applyNotification(qc: QueryClient, n: AppNotification): { removedFromTeam?: number } {
  void qc.invalidateQueries({ queryKey: queryKeys.notifications })
  if (n.type === 'REMOVED_FROM_TEAM' && n.teamId) {
    qc.removeQueries({ queryKey: queryKeys.team(n.teamId) })
    void qc.invalidateQueries({ queryKey: queryKeys.teams })
    return { removedFromTeam: n.teamId }
  }
  if (n.type !== 'TEAM_INVITE') {
    void qc.invalidateQueries({ queryKey: queryKeys.teams })
    if (n.teamId) void qc.invalidateQueries({ queryKey: queryKeys.team(n.teamId) })
  }
  return {}
}
