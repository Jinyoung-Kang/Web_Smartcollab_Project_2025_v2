import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { folderApi } from '@/api/endpoints'
import { queryKeys } from '@/api/queryKeys'
import type { Permissions } from '@/api/types'

const NO_PERMISSIONS: Permissions = { canEdit: false, canDelete: false, canInvite: false, leader: false }

/**
 * 폴더 내용·내 권한·스코프. 주소의 스코프(/drive 또는 /teams/:id)가 실제 폴더와 다르면 바로잡을 주소를 줍니다.
 */
export function useDriveFolder(folderId: number, routeTeamId?: number) {
  const contents = useQuery({ queryKey: queryKeys.folder.of(folderId), queryFn: () => folderApi.contents(folderId) })
  const data = contents.data
  const items = useMemo(() => data?.items ?? [], [data])
  // 로딩 중에도 팀 패널이 깜박이지 않도록 주소의 팀 ID 를 먼저 씁니다.
  const teamId = data ? data.folder.teamId : routeTeamId
  const permissions = data?.permissions ?? NO_PERMISSIONS
  const redirectTo = data && data.folder.teamId !== routeTeamId
    ? (data.folder.teamId ? `/teams/${data.folder.teamId}/folders/${folderId}` : `/drive/${folderId}`)
    : null
  return { contents, data, items, teamId, permissions, redirectTo }
}
