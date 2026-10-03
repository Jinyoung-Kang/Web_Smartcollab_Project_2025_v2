import { useCallback, useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { fileApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import type { TextContent } from '@/api/types'
import { useToast } from '@/components/ui/Toast'
import { keepDraft } from './keepDraft'

/**
 * 편집 중인 문서: 초안·마지막 저장 내용·편집을 시작한 버전, 저장(Ctrl/⌘+S), 충돌 해결.
 * 저장 때 편집을 시작한 버전 ID 를 함께 보내, 그 사이 다른 사람이 저장했다면 덮어쓰지 않고 충돌로 알립니다(낙관적 잠금).
 */
export function useTextDocument(fileId: number, initial: TextContent) {
  const qc = useQueryClient()
  const toast = useToast()
  const [saved, setSaved] = useState(initial.content)
  const [draft, setDraft] = useState(initial.content)
  const [baseVersion, setBaseVersion] = useState(initial.versionId)
  const [saving, setSaving] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [savedAt, setSavedAt] = useState<string | null>(initial.updatedAt)
  const dirty = draft !== saved
  const editable = initial.editable

  const fetchLatest = () => qc.fetchQuery({ queryKey: queryKeys.fileContent(fileId), queryFn: () => fileApi.content(fileId), staleTime: 0 })
  const adopt = (latest: TextContent) => {
    setDraft(latest.content)
    setSaved(latest.content)
    setBaseVersion(latest.versionId)
  }

  const save = useCallback(async () => {
    if (!editable || saving) return
    setSaving(true)
    try {
      const res = await fileApi.save(fileId, draft, baseVersion)
      setBaseVersion(res.versionId)
      setSavedAt(res.updatedAt)
      setSaved(draft)
      setConflict(false)
      qc.setQueryData(queryKeys.fileContent(fileId), (old: TextContent | undefined) => old && { ...old, content: draft, versionId: res.versionId })
      void qc.invalidateQueries({ queryKey: queryKeys.versions.of(fileId) })
      void qc.invalidateQueries({ queryKey: queryKeys.folder.all })
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

  /** 충돌 해결 1: 최신 내용을 불러오고, 내 편집본은 클립보드(쓸 수 없으면 파일)로 보관 */
  // 실패하면 알리고 충돌 안내를 그대로 둡니다(이전에는 처리되지 않은 오류로 아무 안내도 없었음) [FB-08].
  const loadLatest = async () => {
    try {
      const keptIn = await keepDraft(draft, initial.name)
      adopt(await fetchLatest())
      setConflict(false)
      toast.info(keptIn === 'clipboard'
        ? '최신 내용을 불러왔습니다. 내가 쓰던 내용은 클립보드에 복사해 두었습니다.'
        : '최신 내용을 불러왔습니다. 클립보드를 쓸 수 없어 내가 쓰던 내용을 파일로 내려받았습니다.')
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  /** 충돌 해결 2: 최신 버전을 기준으로 내 내용을 새 버전으로 저장 (이전 버전은 기록에 남음) */
  const overwrite = async () => {
    try {
      const latest = await fileApi.content(fileId)
      const res = await fileApi.save(fileId, draft, latest.versionId)
      setConflict(false)
      setBaseVersion(res.versionId)
      setSavedAt(res.updatedAt)
      setSaved(draft)
      qc.setQueryData(queryKeys.fileContent(fileId), { ...latest, content: draft, versionId: res.versionId })
      toast.success('내 내용으로 새 버전을 저장했습니다. 상대방의 버전은 버전 기록에 남아 있습니다.')
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  /** 버전을 되돌린 뒤 최신 내용을 다시 불러옵니다 (변경 중이 아닐 때만) */
  const reloadIfClean = () => {
    if (dirty) return
    void fetchLatest().then(adopt, (e: Error) => toast.error(`최신 내용을 불러오지 못했습니다. ${e.message}`))
  }

  return { draft, setDraft, dirty, editable, saving, savedAt, conflict, save, loadLatest, overwrite, reloadIfClean }
}
