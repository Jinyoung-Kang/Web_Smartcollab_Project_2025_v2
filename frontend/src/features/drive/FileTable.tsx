import { useMemo, useState, type KeyboardEvent } from 'react'
import { ArrowDown, ArrowUp, MoreHorizontal } from 'lucide-react'
import type { Item } from '@/api/types'
import { Avatar } from '@/components/ui/misc'
import { ItemIcon } from '@/lib/fileIcons'
import { collator, formatBytes, formatRelative, formatDateTime } from '@/lib/format'
import { cn } from '@/lib/cn'

export type SortKey = 'name' | 'updatedAt' | 'ownerName' | 'size'
export const itemKey = (item: Pick<Item, 'type' | 'id'>) => `${item.type}-${item.id}`

interface FileTableProps {
  items: Item[]
  selected: ReadonlySet<string>
  onSelectionChange: (next: Set<string>) => void
  onOpen: (item: Item) => void
  onContextAction: (item: Item, anchor: HTMLElement) => void
  onDeleteKey?: () => void
  onRenameKey?: () => void
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

export function FileTable({ items, selected, onSelectionChange, onOpen, onContextAction, onDeleteKey, onRenameKey }: FileTableProps) {
  const [sort, setSort] = useState<{ key: SortKey; dir: 'asc' | 'desc' }>({ key: 'name', dir: 'asc' })
  const sorted = useMemo(() => sortItems(items, sort.key, sort.dir), [items, sort])
  const allSelected = sorted.length > 0 && sorted.every((i) => selected.has(itemKey(i)))

  const toggle = (item: Item, additive: boolean) => {
    const key = itemKey(item)
    const next = new Set(additive ? selected : [])
    if (additive && selected.has(key)) next.delete(key)
    else next.add(key)
    onSelectionChange(next)
  }

  const onRowKey = (e: KeyboardEvent<HTMLTableRowElement>, item: Item, index: number) => {
    const rows = e.currentTarget.parentElement?.querySelectorAll<HTMLTableRowElement>('tr[data-row]')
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      rows?.[index + (e.key === 'ArrowDown' ? 1 : -1)]?.focus()
    } else if (e.key === 'Enter') {
      onOpen(item)
    } else if (e.key === ' ') {
      e.preventDefault()
      toggle(item, true)
    } else if (e.key === 'Delete' || e.key === 'Backspace') {
      if (!selected.has(itemKey(item))) onSelectionChange(new Set([itemKey(item)]))
      onDeleteKey?.()
    } else if (e.key === 'F2') {
      onSelectionChange(new Set([itemKey(item)]))
      onRenameKey?.()
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
                  onClick={() => setSort((s) => ({ key: col.key, dir: s.key === col.key && s.dir === 'asc' ? 'desc' : 'asc' }))}
                >
                  {col.label}
                  {active && (sort.dir === 'asc' ? <ArrowUp className="size-3.5" /> : <ArrowDown className="size-3.5" />)}
                </button>
              </th>
            )
          })}
          <th className="w-12 border-b border-slate-200" aria-label="작업" />
        </tr>
      </thead>
      <tbody>
        {sorted.map((item, index) => {
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
              <td className="hidden border-b border-slate-100 px-3 py-2 text-slate-500 sm:table-cell" title={formatDateTime(item.updatedAt)}>
                {formatRelative(item.updatedAt)}
              </td>
              <td className="hidden border-b border-slate-100 px-3 py-2 text-right text-slate-500 tabular-nums lg:table-cell">
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
      </tbody>
    </table>
  )
}
