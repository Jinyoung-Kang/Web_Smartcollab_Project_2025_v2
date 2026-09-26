import { useState } from 'react'
import { useParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { RotateCcw, Trash2 } from 'lucide-react'
import { teamApi, trashApi } from '@/api/endpoints'
import type { TrashItem } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { useConfirm } from '@/components/ui/Confirm'
import { EmptyState, Spinner } from '@/components/ui/misc'
import { useToast } from '@/components/ui/Toast'
import { ItemIcon } from '@/lib/fileIcons'
import { formatBytes, formatDateTime, formatRelative } from '@/lib/format'

const RETENTION_DAYS = 30

/**
 * 휴지통. v1 은 휴지통 API 만 있고 화면이 없어, 삭제한 파일을 되살리거나 비울 수 없었습니다.
 */
export default function TrashPage() {
  const params = useParams()
  const teamId = params.teamId ? Number(params.teamId) : undefined
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const team = useQuery({ queryKey: ['team', teamId], queryFn: () => teamApi.detail(teamId!), enabled: !!teamId })
  const trash = useQuery({ queryKey: ['trash', teamId ?? 'personal'], queryFn: () => trashApi.list(teamId) })

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['trash'] })
    void qc.invalidateQueries({ queryKey: ['folder'] })
    void qc.invalidateQueries({ queryKey: ['usage'] })
  }

  const restore = useMutation({
    mutationFn: (item: TrashItem) => trashApi.restore(item.id),
    onSuccess: (_, item) => { refresh(); toast.success(`'${item.name}'을(를) 원래 폴더로 복원했습니다.`) },
    onError: (e: Error) => toast.error(e.message),
  })
  const purge = useMutation({
    mutationFn: (item: TrashItem) => trashApi.purge(item.id),
    onSuccess: () => { refresh(); toast.success('영구 삭제했습니다.') },
    onError: (e: Error) => toast.error(e.message),
  })
  const empty = useMutation({
    mutationFn: () => trashApi.empty(teamId),
    onSuccess: (r) => { refresh(); toast.success(`${r.deleted}개 파일을 영구 삭제했습니다.`) },
    onError: (e: Error) => toast.error(e.message),
  })

  const askPurge = async (item: TrashItem) => {
    if (await confirm({ title: '영구 삭제할까요?', message: `'${item.name}'과(와) 모든 버전 기록이 삭제되며 되돌릴 수 없습니다.`, confirmLabel: '영구 삭제', danger: true })) {
      purge.mutate(item)
    }
  }
  const askEmpty = async () => {
    if (await confirm({ title: '휴지통을 비울까요?', message: `${trash.data?.length ?? 0}개 파일이 영구 삭제됩니다.`, confirmLabel: '비우기', danger: true })) {
      empty.mutate()
    }
  }

  // 남은 보관 일수 계산 기준 시각 (렌더마다 바뀌지 않도록 한 번만 읽음)
  const [now] = useState(() => Date.now())
  const title = teamId ? `${team.data?.name ?? '팀'} 휴지통` : '휴지통'

  return (
    <div className="flex h-full flex-col bg-white">
      <div className="flex flex-wrap items-center gap-3 border-b border-slate-200 px-4 py-4 sm:px-6">
        <div className="min-w-0 flex-1">
          <h1 className="text-lg font-semibold">{title}</h1>
          <p className="text-sm text-slate-500">삭제한 파일은 {RETENTION_DAYS}일 동안 보관된 뒤 자동으로 영구 삭제됩니다.</p>
        </div>
        <Button variant="danger" disabled={!trash.data?.length} loading={empty.isPending} onClick={askEmpty}>
          <Trash2 className="size-4" /> 휴지통 비우기
        </Button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {trash.isPending && <Spinner className="p-6" />}
        {trash.error && <p className="p-6 text-sm text-red-600">{(trash.error as Error).message}</p>}
        {trash.data?.length === 0 && <EmptyState icon={Trash2} title="휴지통이 비어 있습니다" />}
        <ul>
          {trash.data?.map((item) => {
            const left = RETENTION_DAYS - Math.floor((now - new Date(item.deletedAt).getTime()) / 86_400_000)
            return (
              <li key={item.id} className="flex items-center gap-3 border-b border-slate-100 px-4 py-3 sm:px-6">
                <ItemIcon type="file" name={item.name} className="size-5" />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{item.name}</p>
                  <p className="text-xs text-slate-500" title={formatDateTime(item.deletedAt)}>
                    {item.deletedByName ?? '알 수 없음'}님이 {formatRelative(item.deletedAt)} 삭제 · {formatBytes(item.size)} ·{' '}
                    <span className={left <= 3 ? 'text-red-600' : ''}>{Math.max(left, 0)}일 후 영구 삭제</span>
                  </p>
                </div>
                <Button size="sm" onClick={() => restore.mutate(item)}>
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
