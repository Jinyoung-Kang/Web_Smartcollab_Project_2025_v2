import type { Item } from '@/api/types'
import { deleteTargets, itemKey, selectAll, toggleSelection } from './driveSelection'

const file = (id: number): Item => ({
  type: 'file', id, name: `${id}.txt`, size: 1, ownerName: '김하늘', createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z', previewKind: 'TEXT', textEditable: true,
})
const folder: Item = { ...file(1), type: 'folder', size: undefined }

describe('driveSelection', () => {
  it('키는 종류와 ID 로 만든다 — 같은 ID 의 폴더와 파일을 구분', () => {
    expect(itemKey(file(1))).not.toBe(itemKey(folder))
  })

  it('그냥 고르면 그것만, 더하기면 넣거나 뺀다 (원래 선택은 바꾸지 않음)', () => {
    const selected = new Set(['file-1'])
    expect(toggleSelection(selected, file(2), false)).toEqual(new Set(['file-2']))
    expect(toggleSelection(selected, file(2), true)).toEqual(new Set(['file-1', 'file-2']))
    expect(toggleSelection(selected, file(1), true)).toEqual(new Set())
    expect(selected).toEqual(new Set(['file-1']))
  })

  it('모두 선택은 받은 항목 전부, Delete 대상은 누른 행이 선택에 있으면 선택 전부(목록 순서)', () => {
    const items = [folder, file(2), file(3)]
    expect(selectAll(items)).toEqual(new Set(['folder-1', 'file-2', 'file-3']))
    expect(deleteTargets(file(3), new Set(['file-3', 'folder-1']), items)).toEqual([folder, file(3)])
    expect(deleteTargets(file(2), new Set(['file-3']), items)).toEqual([file(2)])
  })
})
