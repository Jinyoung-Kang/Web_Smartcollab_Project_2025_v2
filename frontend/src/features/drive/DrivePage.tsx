import { useEffect, useMemo, useRef, useState, type DragEvent } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ChevronRight,
  Copy,
  Download,
  FolderInput,
  FolderPlus,
  FolderX,
  History,
  PanelRightOpen,
  PencilLine,
  Share2,
  Trash2,
  Upload,
  UploadCloud,
  X,
} from 'lucide-react'
import { fileApi, folderApi, itemApi, teamApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { Item, ItemRef } from '@/api/types'
import { useMe } from '@/auth/AuthProvider'
import { Button, IconButton, buttonStyles } from '@/components/ui/Button'
import { useConfirm } from '@/components/ui/Confirm'
import { EmptyState, Spinner } from '@/components/ui/misc'
import { useToast } from '@/components/ui/Toast'
import { TeamPanel } from '@/features/team/TeamPanel'
import { cn } from '@/lib/cn'
import { useMediaQuery } from '@/lib/useMediaQuery'
import { FileTable, itemKey } from './FileTable'
import { useUploads } from './UploadProvider'
import { MoveCopyDialog } from './dialogs/MoveCopyDialog'
import { NameDialog } from './dialogs/NameDialog'
import { PreviewDialog } from './dialogs/PreviewDialog'
import { ShareDialog } from './dialogs/ShareDialog'
import { VersionHistoryDialog } from './dialogs/VersionHistoryDialog'

type DialogState =
  | { kind: 'none' }
  | { kind: 'newFolder' }
  | { kind: 'rename'; item: Item }
  | { kind: 'move' | 'copy' }
  | { kind: 'share'; item: Item }
  | { kind: 'versions'; item: Item }
  | { kind: 'preview'; item: Item }

/** /teams/:teamId → 팀 루트 폴더로 이동 */
export function TeamRootRedirect() {
  const teamId = Number(useParams().teamId)
  const team = useQuery({ queryKey: ['team', teamId], queryFn: () => teamApi.detail(teamId) })
  if (team.error) return <Navigate to="/drive" replace />
  if (!team.data) return <div className="p-8"><Spinner /></div>
  return <Navigate to={`/teams/${teamId}/folders/${team.data.rootFolderId}`} replace />
}

export function DrivePage() {
  const me = useMe()
  const params = useParams()
  const folderId = params.folderId ? Number(params.folderId) : me.rootFolderId
  const routeTeamId = params.teamId ? Number(params.teamId) : undefined
  // 폴더가 바뀌면 선택·메뉴·대화상자 상태를 모두 새로 시작합니다 (key 로 컴포넌트 재생성).
  return <DriveView key={folderId} folderId={folderId} routeTeamId={routeTeamId} />
}

function DriveView({ folderId, routeTeamId }: { folderId: number; routeTeamId?: number }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const uploads = useUploads()

  const contents = useQuery({ queryKey: ['folder', folderId], queryFn: () => folderApi.contents(folderId) })
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [dialog, setDialog] = useState<DialogState>({ kind: 'none' })
  const [menu, setMenu] = useState<{ item: Item; x: number; y: number } | null>(null)
  const [dragging, setDragging] = useState(false)
  const [panelOpen, setPanelOpen] = useState(false)
  const wide = useMediaQuery('(min-width: 1280px)')
  const dragDepth = useRef(0)
  const fileInput = useRef<HTMLInputElement>(null)

  const data = contents.data
  // 로딩 중에도 팀 패널이 깜박이지 않도록 주소의 팀 ID 를 먼저 씁니다.
  const teamId = data ? data.folder.teamId : routeTeamId
  const permissions = data?.permissions ?? { canEdit: false, canDelete: false, canInvite: false, leader: false }
  const items = useMemo(() => data?.items ?? [], [data])
  const selectedItems = useMemo(() => items.filter((i) => selected.has(itemKey(i))), [items, selected])
  const single = selectedItems.length === 1 ? selectedItems[0] : undefined
  const singleFile = single?.type === 'file' ? single : undefined

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['folder', folderId] })
    void qc.invalidateQueries({ queryKey: ['tree'] })
    void qc.invalidateQueries({ queryKey: ['usage'] })
  }

  // 선택한 항목을 한 요청으로 지웁니다. 하나라도 지울 수 없으면 아무것도 지우지 않습니다 [PERF-03].
  const remove = useMutation({
    mutationFn: (targets: Item[]) => itemApi.remove(targets.map((t) => ({ type: t.type, id: t.id }))),
    onSuccess: ({ trashedFiles, deletedFolders }) => {
      setSelected(new Set())
      toast.success(deletedFolders === 0
        ? `${trashedFiles}개 파일을 휴지통으로 옮겼습니다.`
        : `${trashedFiles + deletedFolders}개 항목을 삭제했습니다.`)
    },
    onError: (e: Error) => toast.error(e.message),
    onSettled: refresh,
  })

  const askDelete = async (targets: Item[]) => {
    if (targets.length === 0) return
    const folders = targets.filter((t) => t.type === 'folder').length
    const ok = await confirm({
      title: `${targets.length}개 항목을 삭제할까요?`,
      message: folders > 0
        ? '파일은 휴지통으로 옮겨지지만, 폴더는 안에 든 파일까지 즉시 영구 삭제됩니다.'
        : '휴지통으로 옮겨지며 30일 안에 복원할 수 있습니다.',
      confirmLabel: '삭제',
      danger: true,
    })
    if (ok) remove.mutate(targets)
  }

  const open = (item: Item) => {
    if (item.type === 'folder') {
      navigate(teamId ? `/teams/${teamId}/folders/${item.id}` : `/drive/${item.id}`)
    } else {
      setDialog({ kind: 'preview', item })
    }
  }

  const refs: ItemRef[] = selectedItems.map((i) => ({ type: i.type, id: i.id }))

  const onDrop = (e: DragEvent) => {
    e.preventDefault()
    dragDepth.current = 0
    setDragging(false)
    if (!permissions.canEdit) return toast.error('이 폴더에 파일을 올릴 권한이 없습니다.')
    const files = Array.from(e.dataTransfer.files)
    if (files.length) uploads.enqueue(folderId, files)
  }

  if (contents.error) {
    const notFound = contents.error instanceof ApiError && contents.error.status === 404
    return (
      <EmptyState
        className="h-full"
        icon={FolderX}
        title={notFound ? '폴더를 찾을 수 없습니다' : '폴더를 불러오지 못했습니다'}
        description={notFound ? '삭제되었거나 접근 권한이 없는 폴더입니다.' : (contents.error as Error).message}
        action={<Link to="/drive" className={buttonStyles('primary')}>내 드라이브로</Link>}
      />
    )
  }

  // 주소의 스코프와 실제 폴더의 스코프가 다르면 올바른 주소로 바로잡습니다.
  if (data && data.folder.teamId !== routeTeamId) {
    const target = data.folder.teamId ? `/teams/${data.folder.teamId}/folders/${folderId}` : `/drive/${folderId}`
    return <Navigate to={target} replace />
  }

  return (
    <div className="flex h-full">
      <section
        className="relative flex min-w-0 flex-1 flex-col"
        onDragEnter={(e) => {
          if (!e.dataTransfer.types.includes('Files')) return
          dragDepth.current++
          setDragging(true)
        }}
        onDragLeave={() => {
          dragDepth.current = Math.max(0, dragDepth.current - 1)
          if (dragDepth.current === 0) setDragging(false)
        }}
        onDragOver={(e) => e.preventDefault()}
        onDrop={onDrop}
      >
        {/* 머리글: 경로 + 주요 작업 */}
        <div className="border-b border-slate-200 bg-white px-4 pt-4 pb-3 sm:px-6">
          <nav aria-label="현재 위치" className="flex min-w-0 items-center gap-1 text-sm text-slate-500">
            {data?.path.map((crumb, i) => {
              const last = i === data.path.length - 1
              const to = teamId ? `/teams/${teamId}/folders/${crumb.id}` : `/drive/${crumb.id}`
              return (
                <span key={crumb.id} className="flex min-w-0 items-center gap-1">
                  {i > 0 && <ChevronRight aria-hidden className="size-4 shrink-0 text-slate-300" />}
                  {last ? (
                    <span aria-current="page" className="truncate text-lg font-semibold text-slate-900">{crumb.name}</span>
                  ) : (
                    <Link to={to} className="truncate rounded px-1 hover:bg-slate-100 hover:text-slate-900">{crumb.name}</Link>
                  )}
                </span>
              )
            })}
            {!data && <span className="h-7 w-40 animate-pulse rounded bg-slate-100" />}
          </nav>
          {selectedItems.length > 0 ? (
            <div role="toolbar" aria-label="선택한 항목 작업"
              className="animate-slide-up mt-3 flex min-h-10 flex-wrap items-center gap-1 rounded-lg bg-brand-50 px-2 py-1">
              <IconButton label="선택 해제" onClick={() => setSelected(new Set())}>
                <X className="size-4" />
              </IconButton>
              <span className="mr-2 text-sm font-medium text-brand-800">{selectedItems.length}개 선택</span>
              {singleFile && (
                <a href={fileApi.downloadUrl(singleFile.id)} download className={buttonStyles('ghost', 'sm')}>
                  <Download className="size-4" /> 내려받기
                </a>
              )}
              {single && permissions.canEdit && (
                <Button size="sm" variant="ghost" onClick={() => setDialog({ kind: 'rename', item: single })}>
                  <PencilLine className="size-4" /> 이름 바꾸기
                </Button>
              )}
              {permissions.canEdit && (
                <Button size="sm" variant="ghost" onClick={() => setDialog({ kind: 'move' })}>
                  <FolderInput className="size-4" /> 이동
                </Button>
              )}
              <Button size="sm" variant="ghost" onClick={() => setDialog({ kind: 'copy' })}>
                <Copy className="size-4" /> 복사
              </Button>
              {singleFile && (
                <>
                  <Button size="sm" variant="ghost" onClick={() => setDialog({ kind: 'share', item: singleFile })}>
                    <Share2 className="size-4" /> 공유
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setDialog({ kind: 'versions', item: singleFile })}>
                    <History className="size-4" /> 버전 기록
                  </Button>
                </>
              )}
              <Button size="sm" variant="ghost" className="text-red-600 hover:bg-red-50" onClick={() => askDelete(selectedItems)}
                loading={remove.isPending}>
                <Trash2 className="size-4" /> 삭제
              </Button>
            </div>
          ) : (
            <div className="mt-3 flex min-h-10 flex-wrap items-center gap-2">
            {permissions.canEdit && (
              <>
                <Button variant="primary" onClick={() => fileInput.current?.click()}>
                  <Upload className="size-4" /> 업로드
                </Button>
                <Button onClick={() => setDialog({ kind: 'newFolder' })}>
                  <FolderPlus className="size-4" /> 새 폴더
                </Button>
                <input
                  ref={fileInput}
                  type="file"
                  multiple
                  hidden
                  onChange={(e) => {
                    const files = Array.from(e.target.files ?? [])
                    if (files.length) uploads.enqueue(folderId, files)
                    e.target.value = ''
                  }}
                />
              </>
            )}
            {data && !permissions.canEdit && (
              <span className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800">읽기 전용 — 편집 권한이 없습니다</span>
            )}
            {teamId && !wide && (
              <Button variant="ghost" className="ml-auto" onClick={() => setPanelOpen(true)}>
                <PanelRightOpen className="size-4" /> 팀 채팅·멤버
              </Button>
            )}
          </div>
          )}
        </div>

        <div className="relative min-h-0 flex-1 overflow-y-auto bg-white">
          {contents.isPending && (
            <div className="grid gap-2 p-6" aria-busy>
              {Array.from({ length: 6 }, (_, i) => <div key={i} className="h-10 animate-pulse rounded-lg bg-slate-100" />)}
            </div>
          )}
          {data && items.length === 0 && (
            <EmptyState
              icon={UploadCloud}
              title="이 폴더는 비어 있습니다"
              description={permissions.canEdit ? '파일을 이곳으로 끌어다 놓거나 업로드 버튼을 누르세요.' : '아직 올라온 파일이 없습니다.'}
            />
          )}
          {data && items.length > 0 && (
            <FileTable
              items={items}
              selected={selected}
              onSelectionChange={setSelected}
              onOpen={open}
              onContextAction={(item, anchor) => {
                const rect = anchor.getBoundingClientRect()
                setSelected(new Set([itemKey(item)]))
                setMenu({ item, x: rect.right, y: rect.bottom })
              }}
              onDeleteKey={() => askDelete(selectedItems)}
              onRenameKey={() => single && permissions.canEdit && setDialog({ kind: 'rename', item: single })}
            />
          )}
          {dragging && (
            <div className="pointer-events-none absolute inset-2 z-20 flex flex-col items-center justify-center rounded-2xl border-2 border-dashed border-brand-400 bg-brand-50/90 text-brand-700">
              <UploadCloud aria-hidden className="size-10" />
              <p className="mt-2 font-semibold">여기에 놓으면 ‘{data?.folder.name}’에 업로드합니다</p>
            </div>
          )}
        </div>
      </section>

      {teamId && (wide ? (
        <aside className="flex w-96 shrink-0 border-l border-slate-200 bg-white">
          <TeamPanel teamId={teamId} />
        </aside>
      ) : (
        <div className={cn('fixed inset-0 z-40', panelOpen ? 'visible' : 'invisible')}>
          <div className={cn('absolute inset-0 bg-slate-900/40 transition-opacity', panelOpen ? 'opacity-100' : 'opacity-0')}
            onClick={() => setPanelOpen(false)} />
          <aside className={cn('absolute inset-y-0 right-0 flex w-96 max-w-[90vw] bg-white shadow-xl transition-transform',
            panelOpen ? 'translate-x-0' : 'translate-x-full')}>
            <TeamPanel teamId={teamId} onClose={() => setPanelOpen(false)} />
          </aside>
        </div>
      ))}

      {/* 행의 … 버튼 메뉴 */}
      {menu && (
        <RowMenu
          x={menu.x}
          y={menu.y}
          item={menu.item}
          canEdit={permissions.canEdit}
          onClose={() => setMenu(null)}
          onAction={(action) => {
            const item = menu.item
            setMenu(null)
            if (action === 'open') open(item)
            if (action === 'rename') setDialog({ kind: 'rename', item })
            if (action === 'move') setDialog({ kind: 'move' })
            if (action === 'copy') setDialog({ kind: 'copy' })
            if (action === 'share') setDialog({ kind: 'share', item })
            if (action === 'versions') setDialog({ kind: 'versions', item })
            if (action === 'delete') void askDelete([item])
          }}
        />
      )}

      <NameDialog
        open={dialog.kind === 'newFolder'}
        title="새 폴더"
        confirmLabel="만들기"
        initial="새 폴더"
        onClose={() => setDialog({ kind: 'none' })}
        onSubmit={async (name) => {
          await folderApi.create(folderId, name)
          refresh()
          toast.success(`'${name}' 폴더를 만들었습니다.`)
        }}
      />
      <NameDialog
        open={dialog.kind === 'rename'}
        title="이름 바꾸기"
        confirmLabel="바꾸기"
        initial={dialog.kind === 'rename' ? dialog.item.name : ''}
        onClose={() => setDialog({ kind: 'none' })}
        onSubmit={async (name) => {
          if (dialog.kind !== 'rename') return
          const { item } = dialog
          await (item.type === 'file' ? fileApi.rename(item.id, name) : folderApi.rename(item.id, name))
          refresh()
          setSelected(new Set())
        }}
      />
      <MoveCopyDialog
        open={dialog.kind === 'move' || dialog.kind === 'copy'}
        mode={dialog.kind === 'copy' ? 'copy' : 'move'}
        count={refs.length}
        scopeTeamId={teamId}
        disabledFolderIds={selectedItems.filter((i) => i.type === 'folder').map((i) => i.id)}
        onClose={() => setDialog({ kind: 'none' })}
        onConfirm={async (target) => {
          if (dialog.kind === 'copy') {
            const res = await itemApi.copy(refs, target)
            toast.success(`파일 ${res.copiedFiles}개를 복사했습니다.`)
          } else {
            await itemApi.move(refs, target)
            toast.success(`${refs.length}개 항목을 옮겼습니다.`)
          }
          setSelected(new Set())
          refresh()
          void qc.invalidateQueries({ queryKey: ['folder', target] })
        }}
      />
      <ShareDialog file={dialog.kind === 'share' ? dialog.item : null} onClose={() => setDialog({ kind: 'none' })} />
      <VersionHistoryDialog
        file={dialog.kind === 'versions' ? dialog.item : null}
        permissions={permissions}
        onClose={() => setDialog({ kind: 'none' })}
      />
      <PreviewDialog file={dialog.kind === 'preview' ? dialog.item : null} onClose={() => setDialog({ kind: 'none' })} />
    </div>
  )
}

type RowAction = 'open' | 'rename' | 'move' | 'copy' | 'share' | 'versions' | 'delete'

function RowMenu({ x, y, item, canEdit, onClose, onAction }: {
  x: number
  y: number
  item: Item
  canEdit: boolean
  onClose: () => void
  onAction: (a: RowAction) => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    ref.current?.querySelector<HTMLButtonElement>('button')?.focus()
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    const onDown = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && onClose()
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onDown)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onDown)
    }
  }, [onClose])

  const entries: { action: RowAction; label: string; show: boolean; danger?: boolean }[] = [
    { action: 'open', label: item.type === 'folder' ? '열기' : '미리보기', show: true },
    { action: 'rename', label: '이름 바꾸기', show: canEdit },
    { action: 'move', label: '이동', show: canEdit },
    { action: 'copy', label: '복사', show: true },
    { action: 'share', label: '공유 링크', show: item.type === 'file' },
    { action: 'versions', label: '버전 기록', show: item.type === 'file' },
    { action: 'delete', label: '삭제', show: true, danger: true },
  ]
  const left = Math.min(x - 180, window.innerWidth - 196)
  const top = Math.min(y + 4, window.innerHeight - 300)

  return (
    <div ref={ref} role="menu" style={{ left, top }}
      className="animate-slide-up fixed z-50 w-44 rounded-xl border border-slate-200 bg-white py-1 shadow-lg">
      {entries.filter((e) => e.show).map((e) => (
        <button key={e.action} role="menuitem" onClick={() => onAction(e.action)}
          className={cn('block w-full px-3 py-2 text-left text-sm', e.danger ? 'text-red-600 hover:bg-red-50' : 'hover:bg-slate-50')}>
          {e.label}
        </button>
      ))}
    </div>
  )
}
