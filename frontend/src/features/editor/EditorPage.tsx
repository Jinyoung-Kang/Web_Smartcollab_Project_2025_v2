import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, ArrowLeft, FileSearch, History, Save } from 'lucide-react'
import { fileApi } from '@/api/endpoints'
import { queryKeys } from '@/api/queryKeys'
import type { Item, Permissions, TextContent } from '@/api/types'
import { Button, IconButton, buttonStyles } from '@/components/ui/Button'
import { EmptyState, Spinner } from '@/components/ui/misc'
import { VersionHistoryDialog } from '@/features/drive/dialogs/VersionHistoryDialog'
import { formatRelative } from '@/lib/format'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { DocumentToolButtons, DocumentToolPanel } from './DocumentTools'
import { useDocumentTools } from './useDocumentTools'
import { useTextDocument } from './useTextDocument'
import { useUnsavedChangesGuard } from './useUnsavedChangesGuard'
import { folderPath } from '@/lib/paths'

/**
 * 텍스트 편집기.
 * - 저장 시 편집을 시작한 버전 ID 를 함께 보내, 그 사이 다른 사람이 저장했다면 덮어쓰지 않고 충돌을 알립니다(낙관적 잠금).
 * - Ctrl/⌘+S 저장. 저장하지 않은 변경이 있으면 창을 닫거나 새로고침할 때(beforeunload),
 *   앱 안의 다른 화면으로 이동할 때(useBlocker) 확인합니다 [BUG-04].
 * - 요약·번역은 서버에 AI 키가 있으면 Claude, 없으면 단어 빈도 "핵심 문장 추출"·DeepL 번역입니다(DocumentTools) [AI-01].
 * 문서 상태는 useTextDocument, 이동 확인은 useUnsavedChangesGuard, 문서 도구는 useDocumentTools 가 맡습니다.
 */
export default function EditorPage() {
  const fileId = Number(useParams().fileId)
  // 편집기는 처음 받은 내용만 쓰므로 창으로 돌아올 때 문서 전체를 다시 받지 않습니다 [L-5]. 저장할 때 버전으로 서버와 맞춰 봅니다.
  const content = useQuery({
    queryKey: queryKeys.fileContent(fileId), queryFn: () => fileApi.content(fileId), staleTime: 0, refetchOnWindowFocus: false,
  })

  // 처음 불러온 내용으로 편집을 시작합니다. 이후 서버 값이 바뀌어도 편집 중인 내용은 덮어쓰지 않습니다.
  // 데이터가 있으면 오류보다 먼저 봅니다 — 다시 연결될 때 하는 새로고침이 실패해도(노트북을 열자마자 네트워크가
  // 없을 때 등) 편집 화면과 저장하지 않은 내용을 그대로 둡니다 [FB-02]. 저장할 때 다시 서버와 맞춰 봅니다.
  if (content.data) return <Editor key={fileId} fileId={fileId} initial={content.data} />
  if (content.error) {
    return (
      <EmptyState className="h-full" titleAs="h1" icon={FileSearch} title="문서를 열 수 없습니다" description={(content.error as Error).message}
        action={<Link to="/drive" className={buttonStyles('primary')}>내 드라이브로</Link>} />
    )
  }
  return <Spinner className="p-8" />
}

function Editor({ fileId, initial }: { fileId: number; initial: TextContent }) {
  useDocumentTitle(`${initial.name} 편집`)
  const navigate = useNavigate()
  const { draft, setDraft, dirty, editable, saving, savedAt, conflict, save, loadLatest, overwrite, reloadIfClean } =
    useTextDocument(fileId, initial)
  const tools = useDocumentTools(fileId)
  const [historyOpen, setHistoryOpen] = useState(false)
  useUnsavedChangesGuard(dirty)

  const backTo = folderPath(initial.folderId, initial.teamId)
  // 저장하지 않은 변경이 있으면 useUnsavedChangesGuard 가 이동 전에 확인을 받습니다.
  const close = () => navigate(backTo)

  const fileItem: Item = {
    type: 'file', id: fileId, name: initial.name, ownerName: '', createdAt: '', updatedAt: initial.updatedAt,
    previewKind: 'TEXT', textEditable: true,
  }
  const permissions: Permissions = { canEdit: editable, canDelete: false, canInvite: false, leader: false }

  return (
    <div className="flex h-full flex-col bg-white">
      <div className="flex flex-wrap items-center gap-2 border-b border-slate-200 px-3 py-2.5 sm:px-5">
        <IconButton label="편집기 닫기" onClick={close}>
          <ArrowLeft className="size-4" />
        </IconButton>
        <div className="min-w-0 flex-1">
          <h1 className="truncate font-semibold">{initial.name}</h1>
          <p className="text-xs text-slate-500">
            {!editable ? '읽기 전용' : dirty ? '저장하지 않은 변경 있음' : savedAt ? `저장됨 · ${formatRelative(savedAt)}` : ''}
            {' · '}{draft.length.toLocaleString()}자
          </p>
        </div>
        <Button size="sm" variant="ghost" onClick={() => setHistoryOpen(true)}>
          <History className="size-4" /> 버전
        </Button>
        <DocumentToolButtons kind="TEXT" tools={tools} />
        {editable && (
          <Button size="sm" variant="primary" onClick={save} loading={saving} disabled={!dirty}>
            <Save className="size-4" /> 저장
          </Button>
        )}
      </div>

      {conflict && (
        <div role="alert" className="flex flex-wrap items-center gap-3 border-b border-amber-200 bg-amber-50 px-5 py-3 text-sm text-amber-900">
          <AlertTriangle aria-hidden className="size-5 shrink-0" />
          <p className="flex-1">
            편집하는 동안 <b>다른 사람이 먼저 저장</b>했습니다. 그대로 저장하면 그 사람의 변경을 덮어쓰게 됩니다.
          </p>
          <Button size="sm" onClick={loadLatest}>최신 내용 불러오기</Button>
          <Button size="sm" variant="danger" onClick={overwrite}>내 내용으로 새 버전 저장</Button>
        </div>
      )}

      <div className="flex min-h-0 flex-1">
        <textarea
          value={draft}
          readOnly={!editable}
          onChange={(e) => setDraft(e.target.value)}
          spellCheck={false}
          aria-label="문서 내용"
          className="min-w-0 flex-1 resize-none p-5 text-[15px] leading-7 text-slate-800 outline-none sm:px-10"
        />
        {tools.tool && (
          <DocumentToolPanel tool={tools.tool} onClose={tools.close}
            className="w-full max-w-sm border-l border-slate-200 max-lg:absolute max-lg:inset-y-0 max-lg:right-0 max-lg:z-20 max-lg:shadow-xl" />
        )}
      </div>
      <VersionHistoryDialog file={historyOpen ? fileItem : null} permissions={permissions} onClose={() => {
        setHistoryOpen(false)
        reloadIfClean()   // 버전을 되돌렸다면 최신 내용으로
      }} />
    </div>
  )
}
