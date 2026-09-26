import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'

interface Props {
  open: boolean
  title: string
  initial?: string
  confirmLabel: string
  onClose: () => void
  onSubmit: (name: string) => Promise<unknown>
}

/** 새 폴더·이름 변경 공용 입력창. 이름 변경 시 확장자를 뺀 부분만 미리 선택합니다. */
export function NameDialog({ open, title, onClose, ...form }: Props) {
  return (
    <Dialog open={open} onClose={onClose} title={title} size="sm">
      {/* Dialog 는 열릴 때만 내용을 렌더하므로, 열 때마다 입력값이 initial 로 새로 시작합니다. */}
      <NameForm {...form} onClose={onClose} />
    </Dialog>
  )
}

function NameForm({ initial = '', confirmLabel, onClose, onSubmit }: Omit<Props, 'open' | 'title'>) {
  const [name, setName] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const input = inputRef.current
    if (!input) return
    input.focus()
    const dot = initial.lastIndexOf('.')
    input.setSelectionRange(0, dot > 0 ? dot : initial.length)
  }, [initial])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) return setError('이름을 입력하세요.')
    if (/[/\\]/.test(trimmed)) return setError('이름에 / 또는 \\ 를 쓸 수 없습니다.')
    setPending(true)
    try {
      await onSubmit(trimmed)
      onClose()
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setPending(false)
    }
  }

  return (
    <form onSubmit={submit} className="grid gap-4">
      <label>
        <span className="sr-only">이름</span>
        <input ref={inputRef} className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={255} />
      </label>
      {error && <p role="alert" className="-mt-2 text-sm text-red-600">{error}</p>}
      <div className="flex justify-end gap-2">
        <Button onClick={onClose}>취소</Button>
        <Button type="submit" variant="primary" loading={pending} disabled={!name.trim()}>
          {confirmLabel}
        </Button>
      </div>
    </form>
  )
}
