import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BadgeCheck, RotateCcw, ShieldAlert, Signature } from 'lucide-react'
import { fileApi } from '@/api/endpoints'
import type { Item, Permissions } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { useConfirm } from '@/components/ui/Confirm'
import { Avatar, Badge, Spinner } from '@/components/ui/misc'
import { useToast } from '@/components/ui/Toast'
import { formatBytes, formatDateTime } from '@/lib/format'

/**
 * 버전 기록 · 복원 · 서명. 각 버전의 SHA-256 앞자리로 내용이 같은지 한눈에 비교할 수 있습니다.
 * v1 은 현재 버전이 무엇인지, 누가 서명했는지 화면에 보여 주지 않았고 서명 버튼도 없었습니다.
 */
export function VersionHistoryDialog({ file, permissions, onClose }: {
  file: Item | null
  permissions: Permissions
  onClose: () => void
}) {
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const versions = useQuery({ queryKey: ['versions', file?.id], queryFn: () => fileApi.versions(file!.id), enabled: !!file })

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['versions', file?.id] })
    void qc.invalidateQueries({ queryKey: ['file-content', file?.id] })
    void qc.invalidateQueries({ queryKey: ['folder'] })
  }

  const restore = useMutation({
    mutationFn: (versionId: number) => fileApi.restore(file!.id, versionId),
    onSuccess: () => {
      refresh()
      toast.success('선택한 버전을 현재 버전으로 되돌렸습니다.')
    },
    onError: (e: Error) => toast.error(e.message),
  })

  const sign = useMutation({
    mutationFn: () => fileApi.sign(file!.id),
    onSuccess: () => {
      refresh()
      toast.success('현재 버전에 서명했습니다.')
    },
    onError: (e: Error) => toast.error(e.message),
  })

  const askRestore = async (versionId: number, when: string) => {
    const ok = await confirm({
      title: '이 버전으로 되돌릴까요?',
      message: `${when} 버전이 현재 버전이 됩니다. 이후 버전도 기록에 그대로 남으며, 기존 서명은 무효가 됩니다.`,
      confirmLabel: '되돌리기',
    })
    if (ok) restore.mutate(versionId)
  }

  return (
    <Dialog
      open={!!file}
      onClose={onClose}
      title="버전 기록"
      description={file?.name}
      size="lg"
      footer={
        <Button variant="primary" onClick={() => sign.mutate()} loading={sign.isPending}>
          <Signature className="size-4" /> 현재 버전에 서명
        </Button>
      }
    >
      {versions.isPending && <Spinner />}
      <ol className="relative grid gap-3">
        {versions.data?.map((v, i) => (
          <li key={v.versionId} className={v.active ? 'rounded-xl border-2 border-brand-200 bg-brand-50/40 p-3' : 'rounded-xl border border-slate-200 p-3'}>
            <div className="flex flex-wrap items-center gap-2">
              <Avatar name={v.editorName} size="sm" />
              <span className="text-sm font-medium">{v.editorName}</span>
              <span className="text-xs text-slate-500">{formatDateTime(v.createdAt)}</span>
              <span className="text-xs text-slate-600">v{(versions.data?.length ?? 0) - i}</span>
              {v.active && <Badge tone="brand">현재 버전</Badge>}
              <span className="ml-auto flex items-center gap-2">
                <code className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-600" title={`SHA-256 ${v.sha256}`}>
                  {v.sha256.slice(0, 10)}
                </code>
                <span className="text-xs text-slate-500">{formatBytes(v.size)}</span>
                {!v.active && permissions.canEdit && (
                  <Button size="sm" onClick={() => askRestore(v.versionId, formatDateTime(v.createdAt))}>
                    <RotateCcw className="size-3.5" /> 되돌리기
                  </Button>
                )}
              </span>
            </div>
            {v.signatures.length > 0 && (
              <ul className="mt-2 flex flex-wrap gap-2">
                {v.signatures.map((s) => (
                  <li
                    key={s.signerName + s.signedAt}
                    className={s.valid ? 'inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 text-xs text-emerald-700' : 'inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600 line-through'}
                    title={s.valid ? '유효한 서명' : '이후 내용이 바뀌어 무효가 된 서명'}
                  >
                    {s.valid ? <BadgeCheck aria-hidden className="size-3.5" /> : <ShieldAlert aria-hidden className="size-3.5" />}
                    {s.signerName} 서명 · {formatDateTime(s.signedAt)}
                  </li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ol>
      <p className="mt-4 text-xs leading-relaxed text-slate-500">
        서명은 개인 파일은 소유자, 팀 파일은 팀장이 할 수 있습니다. 서명 당시 버전의 SHA-256 해시를 함께 기록하며, 이후 내용이
        바뀌면 서명은 무효로 표시됩니다.
      </p>
    </Dialog>
  )
}
