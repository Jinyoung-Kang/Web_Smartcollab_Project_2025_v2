import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, ArrowLeft, Copy, FileSearch, History, Languages, Save, Sparkles, X } from 'lucide-react'
import { fileApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { Item, Permissions, TextContent } from '@/api/types'
import { usePublicConfig } from '@/auth/AuthProvider'
import { Button, IconButton, buttonStyles } from '@/components/ui/Button'
import { useConfirm } from '@/components/ui/Confirm'
import { EmptyState, Spinner } from '@/components/ui/misc'
import { useToast } from '@/components/ui/Toast'
import { VersionHistoryDialog } from '@/features/drive/dialogs/VersionHistoryDialog'
import { formatRelative } from '@/lib/format'

type ToolResult =
  | { kind: 'summary'; sentences: string[]; total: number }
  | { kind: 'translation'; text: string; target: string }
  | { kind: 'loading'; label: string }
  | { kind: 'error'; message: string }

/**
 * 텍스트 편집기.
 * - 저장 시 편집을 시작한 버전 ID 를 함께 보내, 그 사이 다른 사람이 저장했다면 덮어쓰지 않고 충돌을 알립니다(낙관적 잠금).
 * - Ctrl/⌘+S 저장, 저장하지 않은 변경이 있으면 창을 닫을 때 경고합니다.
 * - 요약은 단어 빈도 기반 "핵심 문장 추출"이며 생성형 AI 결과가 아닙니다. 번역은 DeepL 키가 있을 때만 켜집니다.
 */
export default function EditorPage() {
  const fileId = Number(useParams().fileId)
  const content = useQuery({ queryKey: ['file-content', fileId], queryFn: () => fileApi.content(fileId), staleTime: 0 })

  if (content.error) {
    return (
      <EmptyState className="h-full" icon={FileSearch} title="문서를 열 수 없습니다" description={(content.error as Error).message}
        action={<Link to="/drive" className={buttonStyles('primary')}>내 드라이브로</Link>} />
    )
  }
  if (!content.data) return <Spinner className="p-8" />
  // 처음 불러온 내용으로 편집을 시작합니다. 이후 서버 값이 바뀌어도 편집 중인 내용은 덮어쓰지 않습니다.
  return <Editor key={fileId} fileId={fileId} initial={content.data} />
}

function Editor({ fileId, initial }: { fileId: number; initial: TextContent }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const config = usePublicConfig()
  const [saved, setSaved] = useState(initial.content)
  const [draft, setDraft] = useState(initial.content)
  const [baseVersion, setBaseVersion] = useState(initial.versionId)
  const [saving, setSaving] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [savedAt, setSavedAt] = useState<string | null>(initial.updatedAt)
  const [tool, setTool] = useState<ToolResult | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)

  const dirty = draft !== saved
  const editable = initial.editable

  useEffect(() => {
    if (!dirty) return
    const warn = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const save = useCallback(async () => {
    if (!editable || saving) return
    setSaving(true)
    try {
      const res = await fileApi.save(fileId, draft, baseVersion)
      setBaseVersion(res.versionId)
      setSavedAt(res.updatedAt)
      setSaved(draft)
      setConflict(false)
      qc.setQueryData(['file-content', fileId], (old: TextContent | undefined) => old && { ...old, content: draft, versionId: res.versionId })
      void qc.invalidateQueries({ queryKey: ['versions', fileId] })
      void qc.invalidateQueries({ queryKey: ['folder'] })
      toast.success('새 버전으로 저장했습니다.')
    } catch (e) {
      if (e instanceof ApiError && e.code === 'EDIT_CONFLICT') setConflict(true)
      else toast.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }, [draft, baseVersion, editable, saving, fileId, qc, toast])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
        e.preventDefault()
        void save()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [save])

  const backTo = initial.teamId ? `/teams/${initial.teamId}/folders/${initial.folderId}` : `/drive/${initial.folderId}`

  const close = async () => {
    if (dirty && !(await confirm({ title: '저장하지 않은 변경이 있습니다', message: '저장하지 않고 나가면 변경 내용이 사라집니다.', confirmLabel: '저장 안 하고 나가기', danger: true }))) return
    navigate(backTo)
  }

  /** 충돌 해결 1: 최신 내용을 불러오고, 내 편집본은 클립보드에 보관 */
  const loadLatest = async () => {
    await navigator.clipboard.writeText(draft).catch(() => undefined)
    const latest = await qc.fetchQuery({ queryKey: ['file-content', fileId], queryFn: () => fileApi.content(fileId), staleTime: 0 })
    setDraft(latest.content)
    setSaved(latest.content)
    setBaseVersion(latest.versionId)
    setConflict(false)
    toast.info('최신 내용을 불러왔습니다. 내가 쓰던 내용은 클립보드에 복사해 두었습니다.')
  }

  /** 충돌 해결 2: 최신 버전을 기준으로 내 내용을 새 버전으로 저장 (이전 버전은 기록에 남음) */
  const overwrite = async () => {
    const latest = await fileApi.content(fileId)
    setBaseVersion(latest.versionId)
    setConflict(false)
    try {
      const res = await fileApi.save(fileId, draft, latest.versionId)
      setBaseVersion(res.versionId)
      setSavedAt(res.updatedAt)
      setSaved(draft)
      qc.setQueryData(['file-content', fileId], { ...latest, content: draft, versionId: res.versionId })
      toast.success('내 내용으로 새 버전을 저장했습니다. 상대방의 버전은 버전 기록에 남아 있습니다.')
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  const runSummary = async () => {
    setTool({ kind: 'loading', label: '핵심 문장을 고르는 중' })
    try {
      const r = await fileApi.summary(fileId)
      setTool({ kind: 'summary', sentences: r.sentences, total: r.totalSentences })
    } catch (e) {
      setTool({ kind: 'error', message: (e as Error).message })
    }
  }

  const runTranslate = async (target: 'EN' | 'KO') => {
    setTool({ kind: 'loading', label: target === 'EN' ? '영어로 번역하는 중' : '한국어로 번역하는 중' })
    try {
      const r = await fileApi.translate(fileId, target)
      setTool({ kind: 'translation', text: r.text, target: r.targetLang })
    } catch (e) {
      setTool({ kind: 'error', message: (e as Error).message })
    }
  }

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
        <Button size="sm" variant="ghost" onClick={runSummary} title="저장된 최신 버전에서 중요한 문장을 고릅니다">
          <Sparkles className="size-4" /> 핵심 문장
        </Button>
        <Button size="sm" variant="ghost" disabled={!config?.translationEnabled} onClick={() => runTranslate('EN')}
          title={config?.translationEnabled ? '영어로 번역 (DeepL)' : '서버에 번역 API 키가 설정되지 않았습니다'}>
          <Languages className="size-4" /> EN
        </Button>
        <Button size="sm" variant="ghost" disabled={!config?.translationEnabled} onClick={() => runTranslate('KO')}
          title={config?.translationEnabled ? '한국어로 번역 (DeepL)' : '서버에 번역 API 키가 설정되지 않았습니다'}>
          <Languages className="size-4" /> KO
        </Button>
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
        {tool && (
          <aside className="flex w-full max-w-sm flex-col border-l border-slate-200 bg-slate-50 max-lg:absolute max-lg:inset-y-0 max-lg:right-0 max-lg:z-20 max-lg:shadow-xl">
            <div className="flex items-center gap-2 border-b border-slate-200 px-4 py-3">
              <p className="flex-1 text-sm font-semibold">
                {tool.kind === 'summary' ? '핵심 문장 (추출 요약)' : tool.kind === 'translation' ? `번역 결과 (${tool.target})` : '문서 도구'}
              </p>
              {tool.kind === 'translation' && (
                <IconButton label="번역 결과 복사" onClick={() => navigator.clipboard.writeText(tool.text).then(() => toast.success('복사했습니다.'))}>
                  <Copy className="size-4" />
                </IconButton>
              )}
              <IconButton label="닫기" onClick={() => setTool(null)}>
                <X className="size-4" />
              </IconButton>
            </div>
            <div className="min-h-0 flex-1 overflow-y-auto p-4 text-sm leading-relaxed">
              {tool.kind === 'loading' && <Spinner label={tool.label} />}
              {tool.kind === 'error' && <p className="text-red-600">{tool.message}</p>}
              {tool.kind === 'summary' && (
                <>
                  <ol className="grid list-decimal gap-2 pl-5">
                    {tool.sentences.map((s) => <li key={s}>{s}</li>)}
                  </ol>
                  <p className="mt-4 text-xs text-slate-500">
                    전체 {tool.total}문장 중 단어 빈도가 높은 문장을 원문 그대로 골랐습니다. 생성형 AI 로 새로 쓴 요약이 아니며,
                    저장된 최신 버전을 기준으로 합니다.
                  </p>
                </>
              )}
              {tool.kind === 'translation' && <p className="whitespace-pre-wrap">{tool.text}</p>}
            </div>
          </aside>
        )}
      </div>
      <VersionHistoryDialog file={historyOpen ? fileItem : null} permissions={permissions} onClose={() => {
        setHistoryOpen(false)
        // 버전 복원 후에는 최신 내용을 다시 불러옵니다 (변경 중이 아닐 때만)
        if (!dirty) {
          void qc.fetchQuery({ queryKey: ['file-content', fileId], queryFn: () => fileApi.content(fileId), staleTime: 0 }).then((latest) => {
            setDraft(latest.content)
            setSaved(latest.content)
            setBaseVersion(latest.versionId)
          })
        }
      }} />
    </div>
  )
}
