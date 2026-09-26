import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react'
import { Button } from './Button'
import { Dialog } from './Dialog'

interface ConfirmOptions {
  title: string
  message?: ReactNode
  confirmLabel?: string
  danger?: boolean
  /** 입력해야 확인되는 문구 (예: 팀 이름) */
  requireText?: string
}

type ConfirmFn = (options: ConfirmOptions) => Promise<boolean>

const ConfirmContext = createContext<ConfirmFn | null>(null)

/**
 * await confirm({...}) 형태의 확인 대화상자. v1 의 confirm()/prompt() 를 대체합니다.
 */
export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [options, setOptions] = useState<ConfirmOptions | null>(null)
  const [typed, setTyped] = useState('')
  const resolver = useRef<((ok: boolean) => void) | null>(null)

  const confirm = useCallback<ConfirmFn>((opts) => {
    setTyped('')
    setOptions(opts)
    return new Promise<boolean>((resolve) => {
      resolver.current = resolve
    })
  }, [])

  const close = (ok: boolean) => {
    resolver.current?.(ok)
    resolver.current = null
    setOptions(null)
  }

  const blocked = options?.requireText !== undefined && typed !== options.requireText

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      <Dialog
        open={options !== null}
        onClose={() => close(false)}
        title={options?.title ?? ''}
        size="sm"
        footer={
          <>
            <Button onClick={() => close(false)}>취소</Button>
            <Button variant={options?.danger ? 'danger' : 'primary'} disabled={blocked} onClick={() => close(true)}>
              {options?.confirmLabel ?? '확인'}
            </Button>
          </>
        }
      >
        {options?.message && <div className="text-sm leading-relaxed text-slate-600">{options.message}</div>}
        {options?.requireText !== undefined && (
          <label className="mt-4 block">
            <span className="label">
              확인을 위해 <b className="text-slate-900">{options.requireText}</b> 을(를) 입력하세요
            </span>
            <input className="input" value={typed} onChange={(e) => setTyped(e.target.value)} autoFocus />
          </label>
        )}
      </Dialog>
    </ConfirmContext.Provider>
  )
}

export function useConfirm(): ConfirmFn {
  const ctx = useContext(ConfirmContext)
  if (!ctx) throw new Error('useConfirm must be used within ConfirmProvider')
  return ctx
}
