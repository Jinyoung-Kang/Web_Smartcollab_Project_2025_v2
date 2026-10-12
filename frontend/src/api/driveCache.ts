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

const BATCH_MS = 300

interface PendingChange {
  folderIds: Set<number>
  allFolders: boolean
  scopes: Set<Scope | undefined>
}

const pending = new WeakMap<QueryClient, PendingChange>()

/**
 * 짧은 시간에 몰려 오는 변경(업로드 묶음·다른 사람의 변경 알림)은 {@link BATCH_MS} 동안 모아 한 번에 다시 받습니다 [M-9].
 * 파일 100개를 올리면 업로드 한 건마다 폴더 목록·사용량을 다시 받아 GET 200건이 나갔습니다(실측). 첫 변경부터 시간을 재므로
 * 긴 묶음 동안에도 목록이 일정 간격으로 갱신됩니다. 사용자가 직접 한 작업(삭제·이동 등)은 {@link invalidateDriveChange} 로 바로 받습니다.
 */
export function scheduleDriveChange(qc: QueryClient, { folderIds, scope }: { folderIds?: number[]; scope?: Scope } = {}) {
  let change = pending.get(qc)
  if (!change) {
    change = { folderIds: new Set(), allFolders: false, scopes: new Set() }
    pending.set(qc, change)
    setTimeout(() => flush(qc), BATCH_MS)
  }
  if (folderIds) folderIds.forEach((id) => change.folderIds.add(id))
  else change.allFolders = true
  change.scopes.add(scope)
}

function flush(qc: QueryClient) {
  const change = pending.get(qc)
  if (!change) return
  pending.delete(qc)
  // 스토리지가 하나면 그 스토리지만, 섞였으면 모든 스토리지의 트리·사용량·휴지통
  const scope = change.scopes.size === 1 ? [...change.scopes][0] : undefined
  invalidateDriveChange(qc, { folderIds: change.allFolders ? undefined : [...change.folderIds], scope })
}
