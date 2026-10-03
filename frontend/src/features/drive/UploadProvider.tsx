import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, ChevronDown, ChevronUp, UploadCloud, X, XCircle } from 'lucide-react'
import { uploadFile } from '@/api/upload'
import { ApiError } from '@/api/http'
import { usePublicConfig } from '@/auth/AuthProvider'
import { IconButton } from '@/components/ui/Button'
import { formatBytes } from '@/lib/format'
import { createTaskQueue } from './uploadQueue'
import { cn } from '@/lib/cn'
import { queryKeys } from '@/api/queryKeys'

interface UploadTask {
  id: number
  name: string
  size: number
  folderId: number
  progress: number
  status: 'queued' | 'uploading' | 'done' | 'error' | 'canceled'
  error?: string
  controller: AbortController
}

interface UploadApi {
  enqueue: (folderId: number, files: File[]) => void
}

const Ctx = createContext<UploadApi | null>(null)
const CONCURRENCY = 3

/**
 * 여러 파일 업로드 대기열 (동시 3개, 진행률·취소·실패 사유 표시).
 * 대기 중인 파일을 취소하면 대기열에서 빼고, 올리는 중인 파일은 요청을 중단합니다 [BUG-03].
 * v1 은 파일 1개만, 진행률 없이 업로드한 뒤 alert() 로 결과를 알렸습니다.
 */
export function UploadProvider({ children }: { children: ReactNode }) {
  const [tasks, setTasks] = useState<UploadTask[]>([])
  const [collapsed, setCollapsed] = useState(false)
  const seq = useRef(0)
  const qc = useQueryClient()
  const config = usePublicConfig()

  // 동시 업로드 3개로 제한하는 대기열 (컴포넌트 수명 동안 하나만 생성)
  const [queue] = useState(() => {
    const patch = (id: number, changes: Partial<UploadTask>) =>
      setTasks((list) => list.map((t) => (t.id === id ? { ...t, ...changes } : t)))
    return createTaskQueue<{ task: UploadTask; file: File }>(CONCURRENCY, async ({ payload: { task, file } }) => {
      patch(task.id, { status: 'uploading' })
      try {
        await uploadFile(task.folderId, file, (p) => patch(task.id, { progress: p }), task.controller.signal)
        patch(task.id, { status: 'done', progress: 1 })
        void qc.invalidateQueries({ queryKey: queryKeys.folder.of(task.folderId) })
        void qc.invalidateQueries({ queryKey: queryKeys.usage.all })
      } catch (e) {
        if (e instanceof ApiError && e.code === 'ABORTED') patch(task.id, { status: 'canceled' })
        else patch(task.id, { status: 'error', error: e instanceof ApiError ? e.message : '업로드 실패' })
      }
    })
  })

  const enqueue = useCallback(
    (folderId: number, files: File[]) => {
      const max = config?.maxUploadBytes
      const created = files.map((file) => {
        const task: UploadTask = {
          id: ++seq.current,
          name: file.name,
          size: file.size,
          folderId,
          progress: 0,
          status: 'queued',
          controller: new AbortController(),
        }
        if (max && file.size > max) {
          task.status = 'error'
          task.error = `최대 ${formatBytes(max)}까지 올릴 수 있습니다.`
        } else if (file.size === 0) {
          task.status = 'error'
          task.error = '빈 파일은 올릴 수 없습니다.'
        }
        return { task, file }
      })
      setTasks((list) => [...list.filter((t) => t.status !== 'done' && t.status !== 'canceled'), ...created.map((c) => c.task)])
      setCollapsed(false)
      created.filter((c) => c.task.status === 'queued').forEach((c) => queue.push({ id: c.task.id, payload: c }))
    },
    [config?.maxUploadBytes, queue],
  )

  const cancel = (task: UploadTask) => {
    if (task.status === 'queued' && queue.cancel(task.id)) {
      setTasks((list) => list.map((t) => (t.id === task.id ? { ...t, status: 'canceled' } : t)))
    } else {
      task.controller.abort()
    }
  }

  const api = useMemo(() => ({ enqueue }), [enqueue])
  const active = tasks.filter((t) => t.status === 'queued' || t.status === 'uploading').length

  return (
    <Ctx.Provider value={api}>
      {children}
      {tasks.length > 0 && (
        <section
          aria-label="업로드 진행 상황"
          className="fixed right-4 bottom-4 z-50 w-[22rem] max-w-[calc(100vw-2rem)] overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xl"
        >
          <header className="flex items-center gap-2 bg-slate-900 px-4 py-2.5 text-sm text-white">
            <UploadCloud aria-hidden className="size-4" />
            <span className="flex-1 font-medium">
              {active > 0 ? `${active}개 업로드 중` : `업로드 완료 (${tasks.filter((t) => t.status === 'done').length}개)`}
            </span>
            <button aria-label={collapsed ? '펼치기' : '접기'} onClick={() => setCollapsed((v) => !v)}>
              {collapsed ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
            </button>
            {active === 0 && (
              <button aria-label="닫기" onClick={() => setTasks([])}>
                <X className="size-4" />
              </button>
            )}
          </header>
          {!collapsed && (
            <ul className="max-h-64 overflow-y-auto">
              {tasks.map((t) => (
                <li key={t.id} className="border-b border-slate-100 px-4 py-2.5 last:border-0">
                  <div className="flex items-center gap-2 text-sm">
                    <span className="min-w-0 flex-1 truncate" title={t.name}>
                      {t.name}
                    </span>
                    {t.status === 'done' && <CheckCircle2 aria-label="완료" className="size-4 text-emerald-500" />}
                    {t.status === 'error' && <XCircle aria-label="실패" className="size-4 text-red-500" />}
                    {(t.status === 'queued' || t.status === 'uploading') && (
                      <IconButton label="업로드 취소" onClick={() => cancel(t)}>
                        <X className="size-3.5" />
                      </IconButton>
                    )}
                  </div>
                  {t.status === 'error' ? (
                    <p className="mt-0.5 text-xs text-red-600">{t.error}</p>
                  ) : t.status === 'canceled' ? (
                    <p className="mt-0.5 text-xs text-slate-500">취소됨</p>
                  ) : (
                    <div className="mt-1.5 flex items-center gap-2">
                      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-slate-100">
                        <div
                          className={cn('h-full rounded-full transition-[width]', t.status === 'done' ? 'bg-emerald-500' : 'bg-brand-500')}
                          style={{ width: `${Math.round(t.progress * 100)}%` }}
                        />
                      </div>
                      <span className="w-16 text-right text-xs text-slate-500">{formatBytes(t.size)}</span>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </Ctx.Provider>
  )
}

export function useUploads(): UploadApi {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('useUploads must be used within UploadProvider')
  return ctx
}
