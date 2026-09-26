import { forwardRef, type ButtonHTMLAttributes } from 'react'
import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/cn'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'subtle'
type Size = 'sm' | 'md'

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-brand-600 text-white hover:bg-brand-700 disabled:bg-brand-600/50',
  secondary: 'border border-slate-300 bg-white text-slate-700 hover:bg-slate-50 disabled:text-slate-400',
  ghost: 'text-slate-600 hover:bg-slate-100 disabled:text-slate-300',
  subtle: 'bg-slate-100 text-slate-700 hover:bg-slate-200 disabled:text-slate-400',
  danger: 'bg-red-600 text-white hover:bg-red-700 disabled:bg-red-600/50',
}

const SIZES: Record<Size, string> = {
  sm: 'h-8 gap-1.5 px-2.5 text-sm',
  md: 'h-10 gap-2 px-4 text-sm',
}

/** <a> 등 버튼이 아닌 요소를 버튼처럼 보이게 할 때 사용 (버튼 안에 링크를 중첩하지 않기 위함) */
export function buttonStyles(variant: Variant = 'secondary', size: Size = 'md', className?: string) {
  return cn(
    'inline-flex shrink-0 items-center justify-center rounded-lg font-medium transition-colors',
    VARIANTS[variant],
    SIZES[size],
    className,
  )
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: Size
  loading?: boolean
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', loading = false, className, children, disabled, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled || loading}
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-lg font-medium transition-colors disabled:cursor-not-allowed',
        VARIANTS[variant],
        SIZES[size],
        className,
      )}
      {...rest}
    >
      {loading && <Loader2 aria-hidden className="size-4 animate-spin" />}
      {children}
    </button>
  )
})

export const IconButton = forwardRef<HTMLButtonElement, ButtonProps & { label: string }>(function IconButton(
  { label, className, variant = 'ghost', ...rest },
  ref,
) {
  return (
    <Button
      ref={ref}
      variant={variant}
      size="sm"
      aria-label={label}
      title={label}
      className={cn('w-8 px-0', className)}
      {...rest}
    />
  )
})
