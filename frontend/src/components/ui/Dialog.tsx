import { useEffect, useId, useRef, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { cn } from '@/lib/cn'
import { IconButton } from './Button'
import { useToastHost } from './Toast'

interface DialogProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  children: ReactNode
  footer?: ReactNode
  size?: 'sm' | 'md' | 'lg' | 'xl'
}

const SIZES = { sm: 'max-w-sm', md: 'max-w-md', lg: 'max-w-2xl', xl: 'max-w-5xl' }

/**
 * 네이티브 <dialog> + showModal() 기반 모달.
 * 포커스 가두기·ESC 닫기·배경 비활성(inert)을 브라우저가 처리하므로 접근성이 기본으로 보장됩니다.
 * (v1 의 모달은 div 오버레이라 키보드로 닫거나 포커스를 가둘 수 없었습니다.)
 */
export function Dialog({ open, onClose, title, description, children, footer, size = 'md' }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const pressedOnBackdrop = useRef(false)
  const titleId = useId()
  const descId = useId()
  useToastHost(ref, open)

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) {
      dialog.showModal()
      // 처음 초점을 받을 입력란(data-autofocus). 자식의 effect·autoFocus 는 이 effect 보다 먼저, 아직 닫힌(보이지 않는) dialog 에서
      // 실행돼 초점이 들어가지 않았고, showModal() 이 첫 단추(닫기)로 초점을 옮겼습니다 [M-7]
      dialog.querySelector<HTMLElement>('[data-autofocus]')?.focus()
    }
    if (!open && dialog.open) dialog.close()
  }, [open])

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={description ? descId : undefined}
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      onMouseDown={(e) => {
        pressedOnBackdrop.current = e.target === ref.current
      }}
      onClick={(e) => {
        // 배경(dialog 요소 자체)을 눌렀다 놓았을 때만 닫기 — 안에서 끌어 선택하다 바깥에서 놓은 click 도 대상이 dialog 라 닫혔음 [L-8]
        if (e.target === ref.current && pressedOnBackdrop.current) onClose()
        pressedOnBackdrop.current = false
      }}
      className={cn(
        'm-auto w-[calc(100%-2rem)] rounded-2xl bg-white p-0 text-slate-800 shadow-2xl',
        SIZES[size],
      )}
    >
      {open && (
        <div className="flex max-h-[85vh] flex-col">
          <div className="flex items-start justify-between gap-4 border-b border-slate-100 px-5 py-4">
            <div className="min-w-0">
              <h2 id={titleId} className="truncate text-base font-semibold">
                {title}
              </h2>
              {description && (
                <p id={descId} className="mt-0.5 text-sm text-slate-500">
                  {description}
                </p>
              )}
            </div>
            <IconButton label="닫기" onClick={onClose}>
              <X className="size-4" />
            </IconButton>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>
          {footer && <div className="flex justify-end gap-2 border-t border-slate-100 px-5 py-3">{footer}</div>}
        </div>
      )}
    </dialog>
  )
}
