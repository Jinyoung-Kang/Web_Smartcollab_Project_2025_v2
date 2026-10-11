import { useState } from 'react'
import { useParams } from 'react-router'
import { RotateCcw, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { EmptyState, Spinner } from '@/components/ui/misc'
import { ItemIcon } from '@/lib/fileIcons'
import { formatBytes, formatDateTime, formatRelative } from '@/lib/format'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { daysLeft, expiresSoon, RETENTION_DAYS } from './trashRules'
import { useTrash } from './useTrash'

/**
 * 휴지통. v1 은 휴지통 API 만 있고 화면이 없어, 삭제한 파일을 되살리거나 비울 수 없었습니다.
 * 데이터와 복원·삭제는 useTrash, 보관 기간·문구 규칙은 trashRules 가 맡습니다.
 */
export default function TrashPage() {
  const params = useParams()
  const teamId = params.teamId ? Number(params.teamId) : undefined
  const { team, trash, restore, isRestoring, askPurge, askEmpty, emptying } = useTrash(teamId)

  // 남은 보관 일수 계산 기준 시각 (렌더마다 바뀌지 않도록 한 번만 읽음)
  const [now] = useState(() => Date.now())
  const title = teamId ? `${team.data?.name ?? '팀'} 휴지통` : '휴지통'
  useDocumentTitle(title)

  return (
    <div className="flex h-full flex-col bg-white">
      <div className="flex flex-wrap items-center gap-3 border-b border-slate-200 px-4 py-4 sm:px-6">
        <div className="min-w-0 flex-1">
          <h1 className="text-lg font-semibold">{title}</h1>
          <p className="text-sm text-slate-500">삭제한 파일과 폴더는 {RETENTION_DAYS}일 동안 보관된 뒤 자동으로 영구 삭제됩니다.</p>
        </div>
        <Button variant="danger" disabled={!trash.data?.length} loading={emptying} onClick={askEmpty}>
          <Trash2 className="size-4" /> 휴지통 비우기
        </Button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {trash.isPending && <Spinner className="p-6" />}
        {trash.error && <p className="p-6 text-sm text-red-600">{(trash.error as Error).message}</p>}
        {trash.data?.length === 0 && <EmptyState icon={Trash2} title="휴지통이 비어 있습니다" />}
        <ul>
          {trash.data?.map((item) => {
            const left = daysLeft(item.deletedAt, now)
            return (
              <li key={`${item.type}-${item.id}`} className="flex items-center gap-3 border-b border-slate-100 px-4 py-3 sm:px-6">
                <ItemIcon type={item.type} name={item.name} className="size-5" />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{item.name}</p>
                  <p className="text-xs text-slate-500" title={formatDateTime(item.deletedAt)}>
                    {item.type === 'folder' && `폴더 · 파일 ${item.fileCount ?? 0}개 · `}
                    {item.deletedByName ?? '알 수 없음'}님이 {formatRelative(item.deletedAt)} 삭제 · {formatBytes(item.size)} ·{' '}
                    <span className={expiresSoon(left) ? 'text-red-600' : ''}>{left}일 후 영구 삭제</span>
                  </p>
                </div>
                <Button size="sm" onClick={() => restore(item)} loading={isRestoring(item)}>
                  <RotateCcw className="size-3.5" /> 복원
                </Button>
                <Button size="sm" variant="ghost" className="text-red-600 hover:bg-red-50" onClick={() => askPurge(item)}>
                  영구 삭제
                </Button>
              </li>
            )
          })}
        </ul>
      </div>
    </div>
  )
}
