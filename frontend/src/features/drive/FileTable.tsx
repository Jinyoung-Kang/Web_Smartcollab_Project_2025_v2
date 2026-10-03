import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { ArrowDown, ArrowUp, MoreHorizontal } from 'lucide-react'
import type { Item } from '@/api/types'
import { Avatar } from '@/components/ui/misc'
import { ItemIcon } from '@/lib/fileIcons'
import { collator, formatBytes, formatRelative, formatDateTime } from '@/lib/format'
import { cn } from '@/lib/cn'

export type SortKey = 'name' | 'updatedAt' | 'ownerName' | 'size'
export const itemKey = (item: Pick<Item, 'type' | 'id'>) => `${item.type}-${item.id}`

/**
 * 한 번에 그리는 행 수 [PERF-02]. 항목이 5,000개인 폴더는 전부 그리면 첫 표시 1.45초, 정렬·전체 선택이 0.2초씩 걸렸습니다(측정).
 * 앞에서부터 이만큼씩 그리고 목록 끝이 보이면 이어서 그립니다. 선택·정렬은 그리지 않은 항목까지 포함한 전체 목록 기준입니다.
 */
export const RENDER_BATCH = 200

interface FileTableProps {
  items: Item[]
  selected: ReadonlySet<string>
  onSelectionChange: (next: Set<string>) => void
  onOpen: (item: Item) => void
  onContextAction: (item: Item, anchor: HTMLElement) => void
  /** Delete·Backspace — 지울 항목을 함께 넘깁니다 */
  onDeleteKey?: (targets: Item[]) => void
  /** F2 — 이름을 바꿀 항목 */
  onRenameKey?: (item: Item) => void
}

const COLUMNS: { key: SortKey; label: string; className: string }[] = [
  { key: 'name', label: '이름', className: 'w-auto' },
  { key: 'ownerName', label: '올린 사람', className: 'hidden w-40 md:table-cell' },
  { key: 'updatedAt', label: '수정한 날짜', className: 'hidden w-36 sm:table-cell' },
  { key: 'size', label: '크기', className: 'hidden w-24 text-right lg:table-cell' },
]

/** 폴더 우선 + 선택한 열 기준 정렬 (한국어 자연 정렬) */
export function sortItems(items: Item[], key: SortKey, dir: 'asc' | 'desc'): Item[] {
  const sign = dir === 'asc' ? 1 : -1
  return [...items].sort((a, b) => {
    if (a.type !== b.type) return a.type === 'folder' ? -1 : 1
    let cmp: number
    switch (key) {
      case 'size':
        cmp = (a.size ?? -1) - (b.size ?? -1)
        break
      case 'updatedAt':
        cmp = a.updatedAt.localeCompare(b.updatedAt)
        break
      case 'ownerName':
        cmp = collator.compare(a.ownerName, b.ownerName)
        break
      default:
        cmp = collator.compare(a.name, b.name)
    }
    return cmp === 0 ? collator.compare(a.name, b.name) : cmp * sign
  })
}

/**
 * Delete 키의 대상 [FB-01]. 키를 누른 행이 선택에 들어 있으면 선택한 항목 모두(화면 순서), 아니면 그 행만입니다.
 * 이전에는 선택을 바꾸자마자 콜백을 불러, 콜백이 쥔 이전 렌더링의 선택 목록(다른 항목)을 지웠습니다.
 */
export function deleteTargets(pressed: Item, selected: ReadonlySet<string>, ordered: Item[]): Item[] {
  return selected.has(itemKey(pressed)) ? ordered.filter((i) => selected.has(itemKey(i))) : [pressed]
}

export function FileTable({ items, selected, onSelectionChange, onOpen, onContextAction, onDeleteKey, onRenameKey }: FileTableProps) {
  const [sort, setSort] = useState<{ key: SortKey; dir: 'asc' | 'desc' }>({ key: 'name', dir: 'asc' })
  const sorted = useMemo(() => sortItems(items, sort.key, sort.dir), [items, sort])
  const allSelected = sorted.length > 0 && sorted.every((i) => selected.has(itemKey(i)))
  const [renderLimit, setRenderLimit] = useState(RENDER_BATCH)
  const visible = sorted.length > renderLimit ? sorted.slice(0, renderLimit) : sorted
  const remaining = sorted.length - visible.length
  const showMore = () => setRenderLimit((n) => n + RENDER_BATCH)
  const moreRef = useRef<HTMLTableRowElement>(null)

  // 목록 끝(더 보기 줄)이 화면에 들어오면 다음 묶음을 그립니다. 버튼도 있어 키보드·보조기기로도 이어 볼 수 있습니다.
  useEffect(() => {
    const el = moreRef.current
    if (!el || remaining === 0 || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) setRenderLimit((n) => n + RENDER_BATCH)
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [remaining, visible.length])

  const toggle = (item: Item, additive: boolean) => {
    const key = itemKey(item)
    const next = new Set(additive ? selected : [])
    if (additive && selected.has(key)) next.delete(key)
    else next.add(key)
    onSelectionChange(next)
  }

  const onRowKey = (e: KeyboardEvent<HTMLTableRowElement>, item: Item, index: number) => {
    const tbody = e.currentTarget.parentElement
    const focusRow = (i: number) => tbody?.querySelectorAll<HTMLTableRowElement>('tr[data-row]')[i]?.focus()
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const next = index + (e.key === 'ArrowDown' ? 1 : -1)
      if (next >= visible.length && remaining > 0) {
        showMore()
        requestAnimationFrame(() => focusRow(next))
      } else {
        focusRow(next)
      }
    } else if ((e.key === 'Enter' || e.key === ' ') && e.target !== e.currentTarget) {
      // 행 안의 버튼·체크박스는 Enter·Space 를 스스로 처리합니다. 행이 또 처리하면 작업 메뉴 버튼의 Enter 가 메뉴와
      // 폴더 열기를 함께 하고, 체크박스의 Space 가 선택을 두 번 바꿨습니다 [FB-09].
    } else if (e.key === 'Enter') {
      onOpen(item)
    } else if (e.key === ' ') {
      e.preventDefault()
      toggle(item, true)
    } else if (e.key === 'Delete' || e.key === 'Backspace') {
      const targets = deleteTargets(item, selected, sorted)
      if (!selected.has(itemKey(item))) onSelectionChange(new Set([itemKey(item)]))
      onDeleteKey?.(targets)
    } else if (e.key === 'F2') {
      onSelectionChange(new Set([itemKey(item)]))
      onRenameKey?.(item)
    } else if (e.key === 'Escape') {
      onSelectionChange(new Set())
    } else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'a') {
      e.preventDefault()
      onSelectionChange(new Set(sorted.map(itemKey)))
    }
  }

  return (
    <table className="w-full table-fixed border-separate border-spacing-0 text-sm">
      <thead className="sticky top-0 z-10 bg-white">
        <tr>
          <th className="w-11 border-b border-slate-200 py-2.5 pl-4 text-left">
            <input
              type="checkbox"
              aria-label="모두 선택"
              className="size-4 rounded accent-brand-600"
              checked={allSelected}
              onChange={() => onSelectionChange(allSelected ? new Set() : new Set(sorted.map(itemKey)))}
            />
          </th>
          {COLUMNS.map((col) => {
            const active = sort.key === col.key
            return (
              <th
                key={col.key}
                aria-sort={active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}
                className={cn('border-b border-slate-200 px-3 py-2.5 text-left font-medium text-slate-500', col.className)}
              >
                <button
                  className={cn('inline-flex items-center gap-1 hover:text-slate-900', active && 'text-slate-900')}
                  onClick={() => {
                    setSort((s) => ({ key: col.key, dir: s.key === col.key && s.dir === 'asc' ? 'desc' : 'asc' }))
                    setRenderLimit(RENDER_BATCH)
                  }}
                >
                  {col.label}
                  {active && (sort.dir === 'asc' ? <ArrowUp className="size-3.5" /> : <ArrowDown className="size-3.5" />)}
                </button>
              </th>
            )
          })}
          <th className="w-12 border-b border-slate-200">
            <span className="sr-only">작업</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {visible.map((item, index) => {
          const key = itemKey(item)
          const isSelected = selected.has(key)
          return (
            <tr
              key={key}
              data-row
              tabIndex={0}
              aria-selected={isSelected}
              onKeyDown={(e) => onRowKey(e, item, index)}
              onClick={(e) => toggle(item, e.metaKey || e.ctrlKey || e.shiftKey)}
              onDoubleClick={() => onOpen(item)}
              className={cn(
                'group cursor-default outline-none select-none',
                isSelected ? 'bg-brand-50' : 'hover:bg-slate-50',
                'focus-visible:bg-brand-50/70',
              )}
            >
              <td className="border-b border-slate-100 py-2 pl-4" onClick={(e) => e.stopPropagation()}>
                <input
                  type="checkbox"
                  aria-label={`${item.name} 선택`}
                  className="size-4 rounded accent-brand-600"
                  checked={isSelected}
                  onChange={() => toggle(item, true)}
                />
              </td>
              <td className="border-b border-slate-100 px-3 py-2">
                <div className="flex min-w-0 items-center gap-3">
                  <ItemIcon type={item.type} name={item.name} className="size-5" />
                  <button
                    className="min-w-0 truncate text-left font-medium text-slate-800 hover:text-brand-700 hover:underline"
                    title={item.name}
                    onClick={(e) => {
                      e.stopPropagation()
                      onOpen(item)
                    }}
                  >
                    {item.name}
                  </button>
                </div>
              </td>
              <td className="hidden border-b border-slate-100 px-3 py-2 md:table-cell">
                <span className="flex items-center gap-2 truncate text-slate-600">
                  <Avatar name={item.ownerName} size="sm" />
                  <span className="truncate">{item.ownerName}</span>
                </span>
              </td>
              <td className="hidden border-b border-slate-100 px-3 py-2 text-slate-600 sm:table-cell" title={formatDateTime(item.updatedAt)}>
                {formatRelative(item.updatedAt)}
              </td>
              <td className="hidden border-b border-slate-100 px-3 py-2 text-right text-slate-600 tabular-nums lg:table-cell">
                {item.type === 'folder' ? '—' : formatBytes(item.size)}
              </td>
              <td className="border-b border-slate-100 pr-3 text-right">
                <button
                  aria-label={`${item.name} 작업 메뉴`}
                  className="rounded-md p-1.5 text-slate-400 opacity-100 hover:bg-slate-200 hover:text-slate-700 focus:opacity-100 sm:opacity-0 sm:group-hover:opacity-100 pointer-coarse:opacity-100"
                  onClick={(e) => {
                    e.stopPropagation()
                    onContextAction(item, e.currentTarget)
                  }}
                >
                  <MoreHorizontal className="size-4" />
                </button>
              </td>
            </tr>
          )
        })}
        {remaining > 0 && (
          <tr ref={moreRef}>
            <td colSpan={COLUMNS.length + 2} className="py-3 text-center">
              <button className="rounded-md px-3 py-1.5 text-sm font-medium text-brand-700 hover:bg-brand-50" onClick={showMore}>
                나머지 {remaining.toLocaleString('ko-KR')}개 더 보기
              </button>
            </td>
          </tr>
        )}
      </tbody>
    </table>
  )
}
