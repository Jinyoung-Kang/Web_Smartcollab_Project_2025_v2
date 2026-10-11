import type { TrashItem } from '@/api/types'

/** 휴지통 보관 기간 — 서버의 자동 영구 삭제 주기와 같은 값 */
export const RETENTION_DAYS = 30
const DAY_MS = 86_400_000

/** 영구 삭제까지 남은 날짜 (0 이상) */
export function daysLeft(deletedAt: string, now: number): number {
  return Math.max(RETENTION_DAYS - Math.floor((now - new Date(deletedAt).getTime()) / DAY_MS), 0)
}

/** 곧 영구 삭제되어 강조할 항목 */
export function expiresSoon(days: number): boolean {
  return days <= 3
}

/** 영구 삭제 확인 문구 — 폴더는 안의 파일까지 함께 지워짐을 알립니다 */
export function purgeMessage(item: TrashItem): string {
  return item.type === 'folder'
    ? `'${item.name}' 폴더와 안의 파일 ${item.fileCount ?? 0}개까지 모두 삭제되며 되돌릴 수 없습니다.`
    : `'${item.name}'과(와) 모든 버전 기록이 삭제되며 되돌릴 수 없습니다.`
}

/** 복원 결과 안내 — 원래 상위 폴더가 휴지통에 있으면 최상위 폴더로 복원됩니다 [UX-06] */
export function restoredMessage(name: string, particle: string, relocated: boolean): string {
  const target = `'${name}'${particle}`
  return relocated
    ? `${target} 최상위 폴더로 복원했습니다. 원래 상위 폴더가 휴지통에 있습니다.`
    : `${target} 원래 폴더로 복원했습니다.`
}
