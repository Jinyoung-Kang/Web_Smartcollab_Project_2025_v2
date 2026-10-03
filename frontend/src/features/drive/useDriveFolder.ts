import { useMemo } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { folderApi } from '@/api/endpoints'
import { queryKeys } from '@/api/queryKeys'
import type { FolderSort, Permissions } from '@/api/types'

const NO_PERMISSIONS: Permissions = { canEdit: false, canDelete: false, canInvite: false, leader: false }

/** 한 번에 받는 항목 수 — 서버 기본값과 같습니다(화면은 이 안에서 200개씩 그림) [IMP-02] */
export const FOLDER_PAGE_SIZE = 500

/**
 * 폴더 내용·내 권한·스코프. 주소의 스코프(/drive 또는 /teams/:id)가 실제 폴더와 다르면 바로잡을 주소를 줍니다.
 * 내용은 서버가 정렬해 묶음으로 주고, 다음 묶음은 {@link loadMore} 로 이어 받습니다 [IMP-02]. 이전에는 항목 전부를 한 번에 받아
 * 화면이 정렬했고, 1만 개 폴더의 응답이 2.31MB 였습니다.
 */
export function useDriveFolder(folderId: number, routeTeamId: number | undefined, sort: FolderSort) {
  const contents = useInfiniteQuery({
    queryKey: queryKeys.folder.sorted(folderId, sort),
    queryFn: ({ pageParam }) =>
      folderApi.contents(folderId, { sort: sort.key, order: sort.dir, cursor: pageParam, limit: FOLDER_PAGE_SIZE }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    // 같은 폴더에서 정렬만 바꾸면 새 정렬을 받는 동안 이전 목록을 보여 줍니다(다른 폴더의 내용은 보여 주지 않음)
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[1] === folderId ? previous : undefined),
  })
  const pages = contents.data?.pages
  const data = pages?.[0]
  const items = useMemo(() => pages?.flatMap((p) => p.items) ?? [], [pages])
  // 묶음을 받는 동안 다른 사람이 항목을 더하거나 빼면 전체 수가 바뀌므로 마지막에 받은 값을 씁니다
  const itemCount = pages?.at(-1)?.itemCount ?? 0
  // 로딩 중에도 팀 패널이 깜박이지 않도록 주소의 팀 ID 를 먼저 씁니다.
  const teamId = data ? data.folder.teamId : routeTeamId
  const permissions = data?.permissions ?? NO_PERMISSIONS
  const redirectTo = data && data.folder.teamId !== routeTeamId
    ? (data.folder.teamId ? `/teams/${data.folder.teamId}/folders/${folderId}` : `/drive/${folderId}`)
    : null
  const loadMore = () => {
    if (contents.hasNextPage && !contents.isFetchingNextPage) void contents.fetchNextPage()
  }
  return { contents, data, items, itemCount, hasMore: contents.hasNextPage, loadMore, teamId, permissions, redirectTo }
}
