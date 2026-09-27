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

describe('FileTable — 항목이 많은 폴더 [PERF-02]', () => {
  const many = Array.from({ length: 450 }, (_, i) => item({ id: i + 1, name: `파일-${String(i).padStart(3, '0')}.txt` }))
  const rowCount = () => document.querySelectorAll('tbody tr[data-row]').length

  it('처음에는 200개만 그리고, 더 보기로 200개씩 이어서 보여 준다', async () => {
    render(<FileTable items={many} selected={new Set()} onSelectionChange={vi.fn()} onOpen={vi.fn()} onContextAction={vi.fn()} />)
    expect(rowCount()).toBe(200)

    await userEvent.click(screen.getByRole('button', { name: '나머지 250개 더 보기' }))
    expect(rowCount()).toBe(400)
    await userEvent.click(screen.getByRole('button', { name: '나머지 50개 더 보기' }))
    expect(rowCount()).toBe(450)
    expect(screen.queryByRole('button', { name: /더 보기/ })).not.toBeInTheDocument()
  })

  it('모두 선택은 아직 그리지 않은 항목까지 고른다', async () => {
    const onChange = vi.fn()
    render(<FileTable items={many} selected={new Set()} onSelectionChange={onChange} onOpen={vi.fn()} onContextAction={vi.fn()} />)
    await userEvent.click(screen.getByLabelText('모두 선택'))
    expect(onChange.mock.lastCall?.[0].size).toBe(450)
  })

  it('키보드로 마지막 행에서 아래로 가면 다음 묶음을 그리고 이어서 이동한다', async () => {
    render(<FileTable items={many} selected={new Set()} onSelectionChange={vi.fn()} onOpen={vi.fn()} onContextAction={vi.fn()} />)
    const rows = document.querySelectorAll<HTMLTableRowElement>('tbody tr[data-row]')
    rows[199]!.focus()
    await userEvent.keyboard('{ArrowDown}')
    await new Promise((resolve) => requestAnimationFrame(resolve))
    expect(rowCount()).toBe(400)
    expect(document.activeElement).toBe(document.querySelectorAll('tbody tr[data-row]')[200])
  })

  it('정렬을 바꾸면 다시 처음 200개부터 그린다', async () => {
    render(<FileTable items={many} selected={new Set()} onSelectionChange={vi.fn()} onOpen={vi.fn()} onContextAction={vi.fn()} />)
    await userEvent.click(screen.getByRole('button', { name: '나머지 250개 더 보기' }))
    await userEvent.click(screen.getByRole('button', { name: '크기' }))
    expect(rowCount()).toBe(200)
  })
})
