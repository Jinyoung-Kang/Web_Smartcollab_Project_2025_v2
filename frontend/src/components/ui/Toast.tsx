import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'
import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react'
import { cn } from '@/lib/cn'

type Tone = 'success' | 'error' | 'info'

interface Toast {
  id: number
  tone: Tone
  message: string
  action?: { label: string; onClick: () => void }
}

interface ToastApi {
  success: (message: string) => void
  error: (message: string) => void
  info: (message: string, action?: Toast['action']) => void
}

const ToastContext = createContext<ToastApi | null>(null)

/**
 * 알림 토스트. v1 은 모든 결과를 alert() 로 띄워 작업 흐름을 매번 막았습니다.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const seq = useRef(0)

  const dismiss = useCallback((id: number) => setToasts((list) => list.filter((t) => t.id !== id)), [])

  const push = useCallback(
    (tone: Tone, message: string, action?: Toast['action']) => {
      const id = ++seq.current
      setToasts((list) => [...list.slice(-3), { id, tone, message, action }])
      window.setTimeout(() => dismiss(id), tone === 'error' ? 6000 : 3500)
    },
    [dismiss],
  )

  const api = useMemo<ToastApi>(
    () => ({
      success: (m) => push('success', m),
      error: (m) => push('error', m),
      info: (m, a) => push('info', m, a),
    }),
    [push],
  )

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div
        aria-live="polite"
        className="pointer-events-none fixed inset-x-0 bottom-5 z-[60] flex flex-col items-center gap-2 px-4"
      >
        {toasts.map((t) => {
          const Icon = t.tone === 'success' ? CheckCircle2 : t.tone === 'error' ? AlertCircle : Info
          return (
            <div
              key={t.id}
              role={t.tone === 'error' ? 'alert' : 'status'}
              className="animate-slide-up pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-xl bg-slate-900 px-4 py-3 text-sm text-white shadow-lg"
            >
              <Icon
                aria-hidden
                className={cn(
                  'mt-0.5 size-4 shrink-0',
                  t.tone === 'success' && 'text-emerald-400',
                  t.tone === 'error' && 'text-red-400',
                  t.tone === 'info' && 'text-sky-400',
                )}
              />
              <p className="flex-1 leading-relaxed">{t.message}</p>
              {t.action && (
                <button
                  className="font-semibold text-sky-300 hover:text-sky-200"
                  onClick={() => {
                    t.action?.onClick()
                    dismiss(t.id)
                  }}
                >
                  {t.action.label}
                </button>
              )}
              <button aria-label="닫기" className="text-slate-400 hover:text-white" onClick={() => dismiss(t.id)}>
                <X className="size-4" />
              </button>
            </div>
          )
        })}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}
