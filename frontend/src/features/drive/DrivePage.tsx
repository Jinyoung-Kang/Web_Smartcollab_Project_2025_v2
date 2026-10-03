import { useMemo, useRef, useState, type DragEvent } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FolderX, UploadCloud } from 'lucide-react'
import { fileApi, folderApi, itemApi, teamApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { Item, ItemRef } from '@/api/types'
import { useMe } from '@/auth/AuthProvider'
import { buttonStyles } from '@/components/ui/Button'
import { useConfirm } from '@/components/ui/Confirm'
import { EmptyState, Spinner } from '@/components/ui/misc'
import { useToast } from '@/components/ui/Toast'
import { TeamPanel } from '@/features/team/TeamPanel'
import { cn } from '@/lib/cn'
import { useMediaQuery } from '@/lib/useMediaQuery'
import { DriveActions, DriveBreadcrumb, SelectionToolbar } from './DriveHeader'
import { FileTable, itemKey } from './FileTable'
import { RowMenu } from './RowMenu'
import { useUploads } from './UploadProvider'
import { MoveCopyDialog } from './dialogs/MoveCopyDialog'
import { NameDialog } from './dialogs/NameDialog'
import { PreviewDialog } from './dialogs/PreviewDialog'
import { ShareDialog } from './dialogs/ShareDialog'
import { VersionHistoryDialog } from './dialogs/VersionHistoryDialog'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { invalidateDriveChange } from '@/api/driveCache'
import { queryKeys } from '@/api/queryKeys'

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
  const team = useQuery({ queryKey: queryKeys.team(teamId), queryFn: () => teamApi.detail(teamId) })
  if (team.error) return <Navigate to="/drive" replace />
  if (!team.data) return <div className="p-8"><Spinner /></div>
  return <Navigate to={`/teams/${teamId}/folders/${team.data.rootFolderId}`} replace />
}

export function DrivePage() {
  const me = useMe()
  const params = useParams()
  const folderId = params.folderId ? Number(params.folderId) : me.rootFolderId
  const routeTeamId = params.teamId ? Number(params.teamId) : undefined
  return <DriveView folderId={folderId} routeTeamId={routeTeamId} />
}

function DriveView({ folderId, routeTeamId }: { folderId: number; routeTeamId?: number }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const uploads = useUploads()

  const contents = useQuery({ queryKey: queryKeys.folder.of(folderId), queryFn: () => folderApi.contents(folderId) })
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [dialog, setDialog] = useState<DialogState>({ kind: 'none' })
  const [menu, setMenu] = useState<{ item: Item; x: number; y: number } | null>(null)
  const [dragging, setDragging] = useState(false)
  const [panelOpen, setPanelOpen] = useState(false)
  const wide = useMediaQuery('(min-width: 1280px)')
  const dragDepth = useRef(0)

  // 폴더가 바뀌면 선택·메뉴·대화상자만 새로 시작합니다. 화면 전체를 다시 만들면(key) 팀 패널까지 다시 만들어져
  // 입력 중이던 채팅·스크롤이 사라지고 접속 표시를 다시 구독했습니다 [FB-07]. 표는 폴더마다 새로 그립니다(key).
  const [shownFolderId, setShownFolderId] = useState(folderId)
  if (shownFolderId !== folderId) {
    setShownFolderId(folderId)
    setSelected(new Set())
    setDialog({ kind: 'none' })
    setMenu(null)
    setDragging(false)
  }

  const data = contents.data
  useDocumentTitle(data?.folder.name)
  // 로딩 중에도 팀 패널이 깜박이지 않도록 주소의 팀 ID 를 먼저 씁니다.
  const teamId = data ? data.folder.teamId : routeTeamId
  const permissions = data?.permissions ?? { canEdit: false, canDelete: false, canInvite: false, leader: false }
  const items = useMemo(() => data?.items ?? [], [data])
  const selectedItems = useMemo(() => items.filter((i) => selected.has(itemKey(i))), [items, selected])

  const refresh = (otherFolderIds: number[] = []) => invalidateDriveChange(qc, { folderIds: [folderId, ...otherFolderIds] })

  // 선택한 항목을 한 요청으로 지웁니다. 하나라도 지울 수 없으면 아무것도 지우지 않습니다 [PERF-03].
  const remove = useMutation({
    mutationFn: (targets: Item[]) => itemApi.remove(targets.map((t) => ({ type: t.type, id: t.id }))),
    onSuccess: ({ trashedFiles, trashedFolders }) => {
      setSelected(new Set())
      toast.success(trashedFolders === 0
        ? `${trashedFiles}개 파일을 휴지통으로 옮겼습니다.`
        : `${trashedFiles + trashedFolders}개 항목을 휴지통으로 옮겼습니다.`)
    },
    onError: (e: Error) => toast.error(e.message),
    onSettled: () => refresh(),
  })

  const askDelete = async (targets: Item[]) => {
    if (targets.length === 0) return
    const folders = targets.filter((t) => t.type === 'folder').length
    const ok = await confirm({
      title: `${targets.length}개 항목을 삭제할까요?`,
      message: folders > 0
        ? '폴더는 안의 폴더·파일과 함께 휴지통으로 옮겨지며, 30일 안에 복원할 수 있습니다.'
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
          <DriveBreadcrumb path={data?.path} teamId={teamId} />
          {selectedItems.length > 0 ? (
            <SelectionToolbar
              selected={selectedItems}
              canEdit={permissions.canEdit}
              deleting={remove.isPending}
              onClear={() => setSelected(new Set())}
              onRename={(item) => setDialog({ kind: 'rename', item })}
              onMove={() => setDialog({ kind: 'move' })}
              onCopy={() => setDialog({ kind: 'copy' })}
              onShare={(file) => setDialog({ kind: 'share', item: file })}
              onVersions={(file) => setDialog({ kind: 'versions', item: file })}
              onDelete={() => askDelete(selectedItems)}
            />
          ) : (
            <DriveActions
              canEdit={permissions.canEdit}
              readOnly={!!data && !permissions.canEdit}
              showPanelButton={!!teamId && !wide}
              onUpload={(files) => uploads.enqueue(folderId, files)}
              onNewFolder={() => setDialog({ kind: 'newFolder' })}
              onOpenPanel={() => setPanelOpen(true)}
            />
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
              key={folderId}
              items={items}
              selected={selected}
              onSelectionChange={setSelected}
              onOpen={open}
              onContextAction={(item, anchor) => {
                const rect = anchor.getBoundingClientRect()
                setSelected(new Set([itemKey(item)]))
                setMenu({ item, x: rect.right, y: rect.bottom })
              }}
              onDeleteKey={(targets) => askDelete(targets)}
              onRenameKey={(item) => permissions.canEdit && setDialog({ kind: 'rename', item })}
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
        <aside aria-label="팀 패널" className="flex w-96 shrink-0 border-l border-slate-200 bg-white">
          <TeamPanel key={teamId} teamId={teamId} visible />
        </aside>
      ) : (
        <div className={cn('fixed inset-0 z-40', panelOpen ? 'visible' : 'invisible')}>
          <div className={cn('absolute inset-0 bg-slate-900/40 transition-opacity', panelOpen ? 'opacity-100' : 'opacity-0')}
            onClick={() => setPanelOpen(false)} />
          <aside aria-label="팀 패널" className={cn('absolute inset-y-0 right-0 flex w-96 max-w-[90vw] bg-white shadow-xl transition-transform',
            panelOpen ? 'translate-x-0' : 'translate-x-full')}>
            <TeamPanel key={teamId} teamId={teamId} visible={panelOpen} onClose={() => setPanelOpen(false)} />
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
          refresh([target])
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
