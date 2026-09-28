import { useRef } from 'react'
import { Link } from 'react-router'
import { ChevronRight, Copy, Download, FolderInput, FolderPlus, History, PanelRightOpen, PencilLine, Share2, Trash2, Upload, X } from 'lucide-react'
import { fileApi } from '@/api/endpoints'
import type { Breadcrumb, Item } from '@/api/types'
import { Button, IconButton, buttonStyles } from '@/components/ui/Button'

/** 현재 위치(경로). 마지막 조각(현재 폴더)이 화면의 제목(h1)입니다. */
export function DriveBreadcrumb({ path, teamId }: { path: Breadcrumb[] | undefined; teamId: number | undefined }) {
  return (
    <nav aria-label="현재 위치" className="flex min-w-0 items-center gap-1 text-sm text-slate-500">
      {path?.map((crumb, i) => {
        const last = i === path.length - 1
        const to = teamId ? `/teams/${teamId}/folders/${crumb.id}` : `/drive/${crumb.id}`
        return (
          <div key={crumb.id} className="flex min-w-0 items-center gap-1">
            {i > 0 && <ChevronRight aria-hidden className="size-4 shrink-0 text-slate-300" />}
            {last ? (
              <h1 aria-current="page" className="truncate text-lg font-semibold text-slate-900">{crumb.name}</h1>
            ) : (
              <Link to={to} className="truncate rounded px-1 hover:bg-slate-100 hover:text-slate-900">{crumb.name}</Link>
            )}
          </div>
        )
      })}
      {!path && <span className="h-7 w-40 animate-pulse rounded bg-slate-100" />}
    </nav>
  )
}

/** 항목을 고르면 나타나는 작업 바 */
export function SelectionToolbar({ selected, canEdit, deleting, onClear, onRename, onMove, onCopy, onShare, onVersions, onDelete }: {
  selected: Item[]
  canEdit: boolean
  deleting: boolean
  onClear: () => void
  onRename: (item: Item) => void
  onMove: () => void
  onCopy: () => void
  onShare: (file: Item) => void
  onVersions: (file: Item) => void
  onDelete: () => void
}) {
  const single = selected.length === 1 ? selected[0] : undefined
  const singleFile = single?.type === 'file' ? single : undefined
  return (
    <div role="toolbar" aria-label="선택한 항목 작업"
      className="animate-slide-up mt-3 flex min-h-10 flex-wrap items-center gap-1 rounded-lg bg-brand-50 px-2 py-1">
      <IconButton label="선택 해제" onClick={onClear}>
        <X className="size-4" />
      </IconButton>
      <span className="mr-2 text-sm font-medium text-brand-800">{selected.length}개 선택</span>
      {singleFile && (
        <a href={fileApi.downloadUrl(singleFile.id)} download className={buttonStyles('ghost', 'sm')}>
          <Download className="size-4" /> 내려받기
        </a>
      )}
      {single && canEdit && (
        <Button size="sm" variant="ghost" onClick={() => onRename(single)}>
          <PencilLine className="size-4" /> 이름 바꾸기
        </Button>
      )}
      {canEdit && (
        <Button size="sm" variant="ghost" onClick={onMove}>
          <FolderInput className="size-4" /> 이동
        </Button>
      )}
      <Button size="sm" variant="ghost" onClick={onCopy}>
        <Copy className="size-4" /> 복사
      </Button>
      {singleFile && (
        <>
          <Button size="sm" variant="ghost" onClick={() => onShare(singleFile)}>
            <Share2 className="size-4" /> 공유
          </Button>
          <Button size="sm" variant="ghost" onClick={() => onVersions(singleFile)}>
            <History className="size-4" /> 버전 기록
          </Button>
        </>
      )}
      <Button size="sm" variant="ghost" className="text-red-600 hover:bg-red-50" onClick={onDelete} loading={deleting}>
        <Trash2 className="size-4" /> 삭제
      </Button>
    </div>
  )
}

/** 아무것도 고르지 않았을 때의 기본 작업 줄 (업로드·새 폴더·팀 패널) */
export function DriveActions({ canEdit, readOnly, showPanelButton, onUpload, onNewFolder, onOpenPanel }: {
  canEdit: boolean
  /** 폴더를 불러왔고 편집 권한이 없음 */
  readOnly: boolean
  showPanelButton: boolean
  onUpload: (files: File[]) => void
  onNewFolder: () => void
  onOpenPanel: () => void
}) {
  const fileInput = useRef<HTMLInputElement>(null)
  return (
    <div className="mt-3 flex min-h-10 flex-wrap items-center gap-2">
      {canEdit && (
        <>
          <Button variant="primary" onClick={() => fileInput.current?.click()}>
            <Upload className="size-4" /> 업로드
          </Button>
          <Button onClick={onNewFolder}>
            <FolderPlus className="size-4" /> 새 폴더
          </Button>
          <input
            ref={fileInput}
            type="file"
            multiple
            hidden
            onChange={(e) => {
              const files = Array.from(e.target.files ?? [])
              if (files.length) onUpload(files)
              e.target.value = ''
            }}
          />
        </>
      )}
      {readOnly && (
        <span className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800">읽기 전용 — 편집 권한이 없습니다</span>
      )}
      {showPanelButton && (
        <Button variant="ghost" className="ml-auto" onClick={onOpenPanel}>
          <PanelRightOpen className="size-4" /> 팀 채팅·멤버
        </Button>
      )}
    </div>
  )
}
