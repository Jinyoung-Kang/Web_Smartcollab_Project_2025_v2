import { useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { FolderX, UploadCloud } from 'lucide-react'
import { teamApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import type { Item } from '@/api/types'
import { useMe } from '@/auth/AuthProvider'
import { buttonStyles } from '@/components/ui/Button'
import { EmptyState, Spinner } from '@/components/ui/misc'
import { useToast } from '@/components/ui/Toast'
import { TeamPanel } from '@/features/team/TeamPanel'
import { cn } from '@/lib/cn'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { useMediaQuery } from '@/lib/useMediaQuery'
import { DriveActions, DriveBreadcrumb, SelectionToolbar } from './DriveHeader'
import { FileTable } from './FileTable'
import { RowMenu } from './RowMenu'
import { useUploads } from './UploadProvider'
import { MoveCopyDialog } from './dialogs/MoveCopyDialog'
import { NameDialog } from './dialogs/NameDialog'
import { PreviewDialog } from './dialogs/PreviewDialog'
import { ShareDialog } from './dialogs/ShareDialog'
import { VersionHistoryDialog } from './dialogs/VersionHistoryDialog'
import { useDriveFolder } from './useDriveFolder'
import { useDriveMutations } from './useDriveMutations'
import { useDriveSelection } from './useDriveSelection'
import { useFileDrop } from './useFileDrop'

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

/**
 * 폴더 화면. 데이터·선택·변경·끌어다 놓기는 훅(useDriveFolder·useDriveSelection·useDriveMutations·useFileDrop)이 맡고,
 * 여기서는 어떤 대화상자·메뉴를 열지와 화면 배치만 정합니다.
 */
function DriveView({ folderId, routeTeamId }: { folderId: number; routeTeamId?: number }) {
  const navigate = useNavigate()
  const toast = useToast()
  const uploads = useUploads()
  const { contents, data, items, teamId, permissions, redirectTo } = useDriveFolder(folderId, routeTeamId)
  const selection = useDriveSelection(items)
  const changes = useDriveMutations({ folderId, onSelectionDone: selection.clear })
  const drop = useFileDrop({
    canDrop: permissions.canEdit,
    onFiles: (files) => uploads.enqueue(folderId, files),
    onRefused: () => toast.error('이 폴더에 파일을 올릴 권한이 없습니다.'),
  })
  const [dialog, setDialog] = useState<DialogState>({ kind: 'none' })
  const [menu, setMenu] = useState<{ item: Item; x: number; y: number } | null>(null)
  const [panelOpen, setPanelOpen] = useState(false)
  const wide = useMediaQuery('(min-width: 1280px)')
  useDocumentTitle(data?.folder.name)

  // 폴더가 바뀌면 선택·메뉴·대화상자만 새로 시작합니다. 화면 전체를 다시 만들면(key) 팀 패널까지 다시 만들어져
  // 입력 중이던 채팅·스크롤이 사라지고 접속 표시를 다시 구독했습니다 [FB-07]. 표는 폴더마다 새로 그립니다(key).
  const [shownFolderId, setShownFolderId] = useState(folderId)
  if (shownFolderId !== folderId) {
    setShownFolderId(folderId)
    selection.clear()
    setDialog({ kind: 'none' })
    setMenu(null)
    drop.reset()
  }

  const { selected, setSelected, selectedItems, refs } = selection
  const { askDelete } = changes

  const open = (item: Item) => {
    if (item.type === 'folder') {
      navigate(teamId ? `/teams/${teamId}/folders/${item.id}` : `/drive/${item.id}`)
    } else {
      setDialog({ kind: 'preview', item })
    }
  }

  if (contents.error) {
    const notFound = contents.error instanceof ApiError && contents.error.status === 404
    return (
      <EmptyState
        className="h-full"
        titleAs="h1"
        icon={FolderX}
        title={notFound ? '폴더를 찾을 수 없습니다' : '폴더를 불러오지 못했습니다'}
        description={notFound ? '삭제되었거나 접근 권한이 없는 폴더입니다.' : (contents.error as Error).message}
        action={<Link to="/drive" className={buttonStyles('primary')}>내 드라이브로</Link>}
      />
    )
  }

  // 주소의 스코프와 실제 폴더의 스코프가 다르면 올바른 주소로 바로잡습니다.
  if (redirectTo) return <Navigate to={redirectTo} replace />

  return (
    <div className="flex h-full">
      <section
        className="relative flex min-w-0 flex-1 flex-col"
        {...drop.handlers}
      >
        {/* 머리글: 경로 + 주요 작업 */}
        <div className="border-b border-slate-200 bg-white px-4 pt-4 pb-3 sm:px-6">
          <DriveBreadcrumb path={data?.path} teamId={teamId} />
          {selectedItems.length > 0 ? (
            <SelectionToolbar
              selected={selectedItems}
              canEdit={permissions.canEdit}
              deleting={changes.deleting}
              onClear={selection.clear}
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
                selection.selectOnly(item)
                setMenu({ item, x: rect.right, y: rect.bottom })
              }}
              onDeleteKey={(targets) => askDelete(targets)}
              onRenameKey={(item) => permissions.canEdit && setDialog({ kind: 'rename', item })}
            />
          )}
          {drop.dragging && (
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
        onSubmit={changes.createFolder}
      />
      <NameDialog
        open={dialog.kind === 'rename'}
        title="이름 바꾸기"
        confirmLabel="바꾸기"
        initial={dialog.kind === 'rename' ? dialog.item.name : ''}
        onClose={() => setDialog({ kind: 'none' })}
        onSubmit={async (name) => {
          if (dialog.kind === 'rename') await changes.rename(dialog.item, name)
        }}
      />
      <MoveCopyDialog
        open={dialog.kind === 'move' || dialog.kind === 'copy'}
        mode={dialog.kind === 'copy' ? 'copy' : 'move'}
        count={refs.length}
        scopeTeamId={teamId}
        disabledFolderIds={selectedItems.filter((i) => i.type === 'folder').map((i) => i.id)}
        onClose={() => setDialog({ kind: 'none' })}
        onConfirm={(target) => changes.transfer(dialog.kind === 'copy' ? 'copy' : 'move', refs, target)}
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
