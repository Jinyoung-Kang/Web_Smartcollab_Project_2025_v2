import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react'
import { createPortal } from 'react-dom'
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

/** 알림을 띄울 곳. 열린 모달이 자신을 올려 두면 맨 위 모달 안에 띄웁니다 [L-5] */
interface ToastHosts {
  register: (host: HTMLElement) => () => void
}

const HostContext = createContext<ToastHosts | null>(null)

/**
 * 알림 토스트. v1 은 모든 결과를 alert() 로 띄워 작업 흐름을 매번 막았습니다.
 * 모달(showModal)이 열려 있으면 그 안에 띄웁니다 — 모달은 최상위 층에 그려지고 바깥을 비활성(inert)으로 만들어, 문서 쪽 알림은
 * 모달 배경 뒤에 가려 닫을 수 없었고 화면 읽기 프로그램도 읽지 않았습니다 [L-5]. 알림을 담는 요소 하나를 맨 위 모달과 문서 사이로
 * 옮기므로, 모달이 닫혀도 떠 있던 알림을 새로 그리지 않습니다.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const [hosts, setHosts] = useState<HTMLElement[]>([])
  const [root] = useState(() => document.createElement('div'))
  const seq = useRef(0)

  useLayoutEffect(() => {
    ;(hosts.at(-1) ?? document.body).appendChild(root)
  }, [hosts, root])
  useEffect(() => () => root.remove(), [root])

  const hostApi = useMemo<ToastHosts>(
    () => ({
      register: (host) => {
        setHosts((list) => [...list, host])
        return () => setHosts((list) => list.filter((h) => h !== host))
      },
    }),
    [],
  )

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

  const viewport = (
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
  )

  return (
    <ToastContext.Provider value={api}>
      <HostContext.Provider value={hostApi}>
        {children}
        {createPortal(viewport, root)}
      </HostContext.Provider>
    </ToastContext.Provider>
  )
}

/** 열린 동안 이 요소(모달) 안에 알림을 띄우게 합니다. 알림 공급자 바깥에서는 아무것도 하지 않습니다. */
export function useToastHost(ref: RefObject<HTMLElement | null>, open: boolean) {
  const hosts = useContext(HostContext)
  useEffect(() => {
    const host = ref.current
    if (!hosts || !open || !host) return
    return hosts.register(host)
  }, [hosts, open, ref])
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}
