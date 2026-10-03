import { useMemo, useState } from 'react'
import type { Item, ItemRef } from '@/api/types'
import { itemKey } from './FileTable'

/** 목록에서 고른 항목. 키(종류-ID)로 보관하고, 지금 목록에 있는 항목만 고른 것으로 칩니다. */
export function useDriveSelection(items: Item[]) {
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const selectedItems = useMemo(() => items.filter((i) => selected.has(itemKey(i))), [items, selected])
  const refs: ItemRef[] = useMemo(() => selectedItems.map((i) => ({ type: i.type, id: i.id })), [selectedItems])
  return {
    selected,
    setSelected,
    selectedItems,
    refs,
    clear: () => setSelected(new Set()),
    selectOnly: (item: Item) => setSelected(new Set([itemKey(item)])),
  }
}
