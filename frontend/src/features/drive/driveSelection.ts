import type { Item } from '@/api/types'

/** 목록 항목의 선택 키(종류-ID). 폴더와 파일의 ID 가 겹칠 수 있어 종류를 함께 씁니다. */
export const itemKey = (item: Pick<Item, 'type' | 'id'>) => `${item.type}-${item.id}`

/**
 * 행을 눌렀을 때의 새 선택. 그냥 누르면 그 행만, 더하기(Ctrl·⌘·Shift·Space·체크박스)면 기존 선택에 넣거나 뺍니다.
 */
export function toggleSelection(selected: ReadonlySet<string>, item: Item, additive: boolean): Set<string> {
  const key = itemKey(item)
  const next = new Set(additive ? selected : [])
  if (additive && selected.has(key)) next.delete(key)
  else next.add(key)
  return next
}

/** 받은 항목을 모두 고른 선택 (아직 그리지 않은 행 포함) */
export function selectAll(items: Item[]): Set<string> {
  return new Set(items.map(itemKey))
}

/**
 * Delete 키의 대상 [FB-01]. 키를 누른 행이 선택에 들어 있으면 선택한 항목 모두(화면 순서), 아니면 그 행만입니다.
 * 이전에는 선택을 바꾸자마자 콜백을 불러, 콜백이 쥔 이전 렌더링의 선택 목록(다른 항목)을 지웠습니다.
 */
export function deleteTargets(pressed: Item, selected: ReadonlySet<string>, ordered: Item[]): Item[] {
  return selected.has(itemKey(pressed)) ? ordered.filter((i) => selected.has(itemKey(i))) : [pressed]
}
