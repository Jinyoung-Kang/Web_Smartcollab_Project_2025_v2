import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronRight, Folder, HardDrive, Users } from 'lucide-react'
import { folderApi, teamApi } from '@/api/endpoints'
import type { TreeNode } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { Spinner } from '@/components/ui/misc'
import { cn } from '@/lib/cn'
import { queryKeys } from '@/api/queryKeys'

interface Props {
  open: boolean
  mode: 'move' | 'copy'
  count: number
  /** 이동은 같은 스토리지 안에서만 가능하므로 현재 스코프만 보여 줍니다. */
  scopeTeamId?: number
  /** 자기 자신이나 하위로 이동하는 것을 막기 위해 선택된 폴더는 비활성화 */
  disabledFolderIds: number[]
  onClose: () => void
  onConfirm: (targetFolderId: number) => Promise<unknown>
}

export function MoveCopyDialog({ open, mode, count, scopeTeamId, disabledFolderIds, onClose, onConfirm }: Props) {
  const [scope, setScope] = useState<number | 'personal'>(scopeTeamId ?? 'personal')
  const [target, setTarget] = useState<number | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 열 때마다 처음 상태로 [FB-04]. 이전에는 지난번에 고른 대상 폴더·스토리지가 남아, 다른 스토리지·자기 하위 폴더가
  // 고른 채로 확인할 수 있었습니다(서버가 거절하지만 혼란). 렌더링 중에 이전 열림 상태와 비교해 바로 맞춥니다.
  const [wasOpen, setWasOpen] = useState(open)
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setScope(scopeTeamId ?? 'personal')
      setTarget(null)
      setError(null)
    }
  }
  const teams = useQuery({ queryKey: queryKeys.teams, queryFn: teamApi.list, enabled: open && mode === 'copy' })
  const effectiveScope = mode === 'move' ? (scopeTeamId ?? 'personal') : scope
  const tree = useQuery({
    queryKey: queryKeys.tree.of(effectiveScope),
    queryFn: () => folderApi.tree(effectiveScope === 'personal' ? undefined : effectiveScope),
    enabled: open,
  })

  const confirm = async () => {
    if (target === null) return
    setPending(true)
    setError(null)
    try {
      await onConfirm(target)
      onClose()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={mode === 'move' ? `${count}개 항목 이동` : `${count}개 항목 복사`}
      description={mode === 'move' ? '같은 스토리지 안에서만 옮길 수 있습니다.' : '다른 팀이나 내 드라이브로도 복사할 수 있습니다.'}
      footer={
        <>
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" disabled={target === null} loading={pending} onClick={confirm}>
            {mode === 'move' ? '여기로 이동' : '여기에 복사'}
          </Button>
        </>
      }
    >
      {mode === 'copy' && (
        <div className="mb-3 flex flex-wrap gap-1.5">
          <ScopeChip active={scope === 'personal'} onClick={() => { setScope('personal'); setTarget(null) }} icon={HardDrive}>
            내 드라이브
          </ScopeChip>
          {teams.data?.filter((t) => t.myPermissions.canEdit).map((t) => (
            <ScopeChip key={t.id} active={scope === t.id} onClick={() => { setScope(t.id); setTarget(null) }} icon={Users}>
              {t.name}
            </ScopeChip>
          ))}
        </div>
      )}
      <div className="h-72 overflow-y-auto rounded-lg border border-slate-200 p-2">
        {tree.isPending && <Spinner className="p-2" />}
        {tree.data?.roots.map((node) => (
          <TreeRow key={node.id} node={node} depth={0} selected={target} onSelect={setTarget} disabled={disabledFolderIds} />
        ))}
      </div>
      {error && <p role="alert" className="mt-3 text-sm text-red-600">{error}</p>}
    </Dialog>
  )
}

function ScopeChip({ active, onClick, icon: Icon, children }: { active: boolean; onClick: () => void; icon: typeof Users; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium',
        active ? 'border-brand-500 bg-brand-50 text-brand-700' : 'border-slate-200 text-slate-600 hover:bg-slate-50',
      )}
    >
      <Icon aria-hidden className="size-3.5" />
      {children}
    </button>
  )
}

function TreeRow({ node, depth, selected, onSelect, disabled }: {
  node: TreeNode
  depth: number
  selected: number | null
  onSelect: (id: number) => void
  disabled: number[]
}) {
  const [expanded, setExpanded] = useState(depth < 1)
  const isDisabled = disabled.includes(node.id)
  return (
    <div>
      <div
        className={cn(
          'flex items-center gap-1 rounded-md py-1 pr-2 text-sm',
          selected === node.id ? 'bg-brand-100 text-brand-800' : 'hover:bg-slate-100',
          isDisabled && 'opacity-40',
        )}
        style={{ paddingLeft: depth * 16 + 4 }}
      >
        <button
          aria-label={expanded ? '접기' : '펼치기'}
          className={cn('rounded p-0.5 text-slate-400 hover:bg-slate-200', node.children.length === 0 && 'invisible')}
          onClick={() => setExpanded((v) => !v)}
        >
          {expanded ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
        </button>
        <button disabled={isDisabled} onClick={() => onSelect(node.id)} className="flex min-w-0 flex-1 items-center gap-2 text-left">
          <Folder aria-hidden className="size-4 shrink-0 fill-amber-200 text-amber-500" />
          <span className="truncate">{node.name}</span>
        </button>
      </div>
      {expanded && !isDisabled &&
        node.children.map((child) => (
          <TreeRow key={child.id} node={child} depth={depth + 1} selected={selected} onSelect={onSelect} disabled={disabled} />
        ))}
    </div>
  )
}
