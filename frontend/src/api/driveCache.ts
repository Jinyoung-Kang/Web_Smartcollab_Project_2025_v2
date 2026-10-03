import type { QueryClient } from '@tanstack/react-query'
import { queryKeys, type Scope } from './queryKeys'

/**
 * 드라이브(폴더·파일)가 바뀐 뒤 다시 불러올 캐시를 한 곳에서 정합니다. 드라이브·휴지통·업로드·채팅 첨부·실시간 변경
 * 다섯 곳이 서로 다르게 무효화해, 지운 뒤 휴지통 목록이 캐시 유효 시간(30초) 동안 옛 목록이었고 채팅으로 올린 파일은
 * 사용량에 바로 반영되지 않았습니다.
 *
 * @param folderIds 내용이 바뀐 폴더. 없으면 모든 폴더 내용
 * @param scope     바뀐 스토리지(팀 ID·'personal'). 없으면 모든 스토리지의 트리·사용량·휴지통
 */
export function invalidateDriveChange(qc: QueryClient, { folderIds, scope }: { folderIds?: number[]; scope?: Scope } = {}) {
  if (folderIds) {
    for (const id of folderIds) void qc.invalidateQueries({ queryKey: queryKeys.folder.of(id) })
  } else {
    void qc.invalidateQueries({ queryKey: queryKeys.folder.all })
  }
  void qc.invalidateQueries({ queryKey: scope === undefined ? queryKeys.tree.all : queryKeys.tree.of(scope) })
  void qc.invalidateQueries({ queryKey: scope === undefined ? queryKeys.usage.all : queryKeys.usage.of(scope) })
  void qc.invalidateQueries({ queryKey: scope === undefined ? queryKeys.trash.all : queryKeys.trash.of(scope) })
  void qc.invalidateQueries({ queryKey: queryKeys.versions.all })
}
