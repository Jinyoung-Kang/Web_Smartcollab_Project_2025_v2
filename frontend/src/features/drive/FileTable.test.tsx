import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Item } from '@/api/types'
import { FileTable, sortItems } from './FileTable'

const item = (over: Partial<Item>): Item => ({
  type: 'file', id: 1, name: 'a.txt', size: 10, ownerName: '김하늘', createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z', previewKind: 'TEXT', textEditable: true, ...over,
})

const items = [
  item({ id: 1, name: '보고서 10.txt', size: 300 }),
  item({ id: 2, name: '보고서 2.txt', size: 100 }),
  item({ id: 3, type: 'folder', name: '자료', size: undefined }),
]

describe('sortItems', () => {
  it('폴더를 먼저, 이름은 자연 정렬', () => {
    expect(sortItems(items, 'name', 'asc').map((i) => i.name)).toEqual(['자료', '보고서 2.txt', '보고서 10.txt'])
  })
  it('크기 내림차순에서도 폴더는 위', () => {
    expect(sortItems(items, 'size', 'desc').map((i) => i.id)).toEqual([3, 1, 2])
  })
})

describe('FileTable', () => {
  it('체크박스로 여러 개를 선택하고, 모두 선택할 수 있다', async () => {
    const onChange = vi.fn()
    render(<FileTable items={items} selected={new Set()} onSelectionChange={onChange} onOpen={vi.fn()} onContextAction={vi.fn()} />)
    await userEvent.click(screen.getByLabelText('모두 선택'))
    expect(onChange).toHaveBeenLastCalledWith(new Set(['folder-3', 'file-2', 'file-1']))
    await userEvent.click(screen.getByLabelText('보고서 2.txt 선택'))
    expect(onChange).toHaveBeenLastCalledWith(new Set(['file-2']))
  })

  it('이름을 누르면 열기, 정렬 방향은 aria-sort 로 알린다', async () => {
    const onOpen = vi.fn()
    render(<FileTable items={items} selected={new Set()} onSelectionChange={vi.fn()} onOpen={onOpen} onContextAction={vi.fn()} />)
    await userEvent.click(screen.getByRole('button', { name: '자료' }))
    expect(onOpen).toHaveBeenCalledWith(items[2])
    const nameHeader = screen.getByRole('columnheader', { name: /이름/ })
    expect(nameHeader).toHaveAttribute('aria-sort', 'ascending')
    await userEvent.click(screen.getByRole('button', { name: /이름/ }))
    expect(nameHeader).toHaveAttribute('aria-sort', 'descending')
  })
})
