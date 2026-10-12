import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { Eraser, Paperclip, SendHorizontal } from 'lucide-react'
import { Button, IconButton } from '@/components/ui/Button'
import { useToast } from '@/components/ui/Toast'
import { validateChatMessage } from './chatMessages'

/**
 * 채팅 입력부: 메시지(Enter 보내기·Shift+Enter 줄바꿈), 파일 첨부, 팀장의 대화 기록 지우기.
 *
 * @param onSend 보냈으면 true. 보내지 못하면 쓴 내용을 입력란에 되돌립니다 [M-4]
 */
export function ChatComposer({ canEdit, canClear, uploading, onSend, onAttach, onClear }: {
  canEdit: boolean
  canClear: boolean
  uploading: boolean
  onSend: (content: string) => Promise<boolean>
  onAttach: (file: File) => void
  onClear: () => void
}) {
  const toast = useToast()
  const [text, setText] = useState('')
  const fileInput = useRef<HTMLInputElement>(null)

  const submit = (e?: FormEvent) => {
    e?.preventDefault()
    const checked = validateChatMessage(text)
    if (!checked) return
    if ('error' in checked) return toast.error(checked.error)
    const written = text
    setText('')
    // 보내지 못하면 쓴 내용을 되돌립니다 — 그사이 새로 쓰기 시작했으면 덮지 않음 [M-4]
    void onSend(checked.content).then((sent) => {
      if (!sent) setText((now) => (now === '' ? written : now))
    })
  }

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit()
    }
  }

  return (
    <form onSubmit={submit} className="border-t border-slate-200 p-3">
      <div className="flex items-end gap-2 rounded-xl border border-slate-300 bg-white p-1.5 focus-within:border-brand-500 focus-within:ring-2 focus-within:ring-brand-100">
        <IconButton label="파일 첨부" onClick={() => fileInput.current?.click()} loading={uploading}
          disabled={!canEdit} title={canEdit ? '파일 첨부' : '편집 권한이 있어야 파일을 올릴 수 있습니다'}>
          {!uploading && <Paperclip className="size-4" />}
        </IconButton>
        <input ref={fileInput} type="file" hidden onChange={(e) => {
          const f = e.target.files?.[0]
          if (f) onAttach(f)
          e.target.value = ''
        }} />
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={onKeyDown}
          rows={1}
          placeholder="메시지 입력 (Shift+Enter 줄바꿈)"
          aria-label="메시지"
          className="max-h-32 min-h-8 flex-1 resize-none bg-transparent px-1 py-1.5 text-sm outline-none"
        />
        <IconButton label="보내기" type="submit" variant="primary" disabled={!text.trim()}>
          <SendHorizontal className="size-4" />
        </IconButton>
      </div>
      {canClear && (
        <Button size="sm" variant="ghost" className="mt-1 text-slate-500" onClick={onClear}>
          <Eraser className="size-3.5" /> 대화 기록 지우기
        </Button>
      )}
    </form>
  )
}
