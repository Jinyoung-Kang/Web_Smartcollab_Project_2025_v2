import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Loader2, type LucideIcon } from 'lucide-react'
import { cn } from '@/lib/cn'

export function Spinner({ className, label = '불러오는 중' }: { className?: string; label?: string }) {
  return (
    <span role="status" className={cn('inline-flex items-center gap-2 text-sm text-slate-500', className)}>
      <Loader2 aria-hidden className="size-4 animate-spin" />
      <span>{label}…</span>
    </span>
  )
}

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
  titleAs: Title = 'p',
}: {
  icon: LucideIcon
  title: string
  description?: ReactNode
  action?: ReactNode
  className?: string
  /** 화면 전체가 이 안내뿐일 때(없는 폴더·문서) 제목을 h1 으로 — 화면에 h1 이 없던 문제 [QA-12] */
  titleAs?: 'p' | 'h1'
}) {
  return (
    <div className={cn('flex flex-col items-center justify-center px-6 py-14 text-center', className)}>
      <div className="mb-4 flex size-14 items-center justify-center rounded-2xl bg-slate-100">
        <Icon aria-hidden className="size-7 text-slate-400" />
      </div>
      <Title className="font-semibold text-slate-700">{title}</Title>
      {description && <p className="mt-1 max-w-sm text-sm text-slate-500">{description}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  )
}

const AVATAR_COLORS = ['bg-sky-500', 'bg-violet-500', 'bg-emerald-500', 'bg-amber-500', 'bg-rose-500', 'bg-indigo-500']

export function Avatar({ name, online, size = 'md' }: { name: string; online?: boolean; size?: 'sm' | 'md' }) {
  const hash = [...name].reduce((a, c) => a + c.charCodeAt(0), 0)
  return (
    <span className="relative inline-flex shrink-0">
      <span
        aria-hidden
        className={cn(
          'inline-flex items-center justify-center rounded-full font-semibold text-white',
          AVATAR_COLORS[hash % AVATAR_COLORS.length],
          size === 'sm' ? 'size-6 text-[11px]' : 'size-8 text-xs',
        )}
      >
        {name.slice(0, 1)}
      </span>
      {online !== undefined && (
        <span
          className={cn(
            'absolute -right-0.5 -bottom-0.5 size-2.5 rounded-full ring-2 ring-white',
            online ? 'bg-emerald-500' : 'bg-slate-300',
          )}
          // 역할 없는 span 의 aria-label 은 보조기기가 읽지 않아(ARIA 금지 속성) 이미지 역할을 줍니다 [QA-10]
          role="img"
          aria-label={online ? '접속 중' : '오프라인'}
        />
      )}
    </span>
  )
}

export function Badge({ children, tone = 'slate' }: { children: ReactNode; tone?: 'slate' | 'brand' | 'green' | 'red' | 'amber' }) {
  const tones = {
    slate: 'bg-slate-100 text-slate-600',
    brand: 'bg-brand-50 text-brand-700',
    green: 'bg-emerald-50 text-emerald-700',
    red: 'bg-red-50 text-red-700',
    amber: 'bg-amber-50 text-amber-700',
  }
  return <span className={cn('inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium', tones[tone])}>{children}</span>
}

/** 버튼을 누르면 열리는 간단한 드롭다운 메뉴 (바깥 클릭·ESC 로 닫힘) */
export function Menu({
  trigger,
  children,
  align = 'right',
  width = 'w-48',
  role = 'menu',
  label,
}: {
  trigger: (props: { open: boolean; toggle: () => void }) => ReactNode
  children: (close: () => void) => ReactNode
  align?: 'left' | 'right'
  width?: string
  /** 메뉴 항목(menuitem)만 담으면 menu, 목록·버튼 등 다른 내용을 담으면 dialog [UX-01] */
  role?: 'menu' | 'dialog'
  label?: string
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div ref={ref} className="relative">
      {trigger({ open, toggle: () => setOpen((v) => !v) })}
      {open && (
        <div
          role={role}
          aria-label={label}
          className={cn(
            'animate-slide-up absolute z-40 mt-1 overflow-hidden rounded-xl border border-slate-200 bg-white py-1 shadow-lg',
            align === 'right' ? 'right-0' : 'left-0',
            width,
          )}
        >
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  )
}

export function MenuItem({
  icon: Icon,
  children,
  onClick,
  danger,
  disabled,
}: {
  icon?: LucideIcon
  children: ReactNode
  onClick: () => void
  danger?: boolean
  disabled?: boolean
}) {
  return (
    <button
      role="menuitem"
      disabled={disabled}
      onClick={onClick}
      className={cn(
        'flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm disabled:cursor-not-allowed disabled:opacity-40',
        danger ? 'text-red-600 hover:bg-red-50' : 'text-slate-700 hover:bg-slate-50',
      )}
    >
      {Icon && <Icon aria-hidden className="size-4" />}
      {children}
    </button>
  )
}
