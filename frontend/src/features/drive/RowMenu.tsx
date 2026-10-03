import { useEffect, useRef } from 'react'
import type { Item } from '@/api/types'
import { cn } from '@/lib/cn'

export type RowAction = 'open' | 'rename' | 'move' | 'copy' | 'share' | 'versions' | 'delete'

/** 목록 행의 … 버튼 메뉴 (ESC·바깥 클릭으로 닫힘, 열리면 첫 항목에 초점) */
export function RowMenu({ x, y, item, canEdit, onClose, onAction }: {
  x: number
  y: number
  item: Item
  canEdit: boolean
  onClose: () => void
  onAction: (a: RowAction) => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  const latestClose = useRef(onClose)
  useEffect(() => {
    latestClose.current = onClose
  })
  // 처음 열릴 때 한 번만 첫 항목에 초점을 둡니다. 부모가 매번 새 onClose 를 넘겨 이 효과가 다시 실행되면,
  // 화면이 다시 그려질 때마다(목록 갱신·실시간 알림) 사용자가 옮긴 초점을 첫 항목으로 빼앗았습니다 [FB-10].
  useEffect(() => {
    ref.current?.querySelector<HTMLButtonElement>('button')?.focus()
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && latestClose.current()
    const onDown = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && latestClose.current()
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onDown)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onDown)
    }
  }, [])

  const entries: { action: RowAction; label: string; show: boolean; danger?: boolean }[] = [
    { action: 'open', label: item.type === 'folder' ? '열기' : '미리보기', show: true },
    { action: 'rename', label: '이름 바꾸기', show: canEdit },
    { action: 'move', label: '이동', show: canEdit },
    { action: 'copy', label: '복사', show: true },
    { action: 'share', label: '공유 링크', show: item.type === 'file' },
    { action: 'versions', label: '버전 기록', show: item.type === 'file' },
    { action: 'delete', label: '삭제', show: true, danger: true },
  ]
  const left = Math.min(x - 180, window.innerWidth - 196)
  const top = Math.min(y + 4, window.innerHeight - 300)

  return (
    <div ref={ref} role="menu" style={{ left, top }}
      className="animate-slide-up fixed z-50 w-44 rounded-xl border border-slate-200 bg-white py-1 shadow-lg">
      {entries.filter((e) => e.show).map((e) => (
        <button key={e.action} role="menuitem" onClick={() => onAction(e.action)}
          className={cn('block w-full px-3 py-2 text-left text-sm', e.danger ? 'text-red-600 hover:bg-red-50' : 'hover:bg-slate-50')}>
          {e.label}
        </button>
      ))}
    </div>
  )
}
