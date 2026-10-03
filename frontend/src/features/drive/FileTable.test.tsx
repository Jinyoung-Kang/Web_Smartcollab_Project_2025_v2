import { fireEvent, render, screen } from '@testing-library/react'
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

// 행 450개를 jsdom 에 그리는 테스트라 느린 환경에서는 기본 제한(5초)을 넘었습니다(동시 실행 재현 6.5초).
describe('FileTable — 항목이 많은 폴더 [PERF-02]', { timeout: 20_000 }, () => {
  const many = Array.from({ length: 450 }, (_, i) => item({ id: i + 1, name: `파일-${String(i).padStart(3, '0')}.txt` }))
  const rowCount = () => document.querySelectorAll('tbody tr[data-row]').length

  // 행이 수백 개면 getByRole(접근성 트리 계산)과 userEvent 가 jsdom 에서 수 초씩 걸려, 글자로 찾고 fireEvent 로 누릅니다.
  const button = (text: string) => screen.getByText(text, { selector: 'button' })
  it('처음에는 200개만 그리고, 더 보기로 200개씩 이어서 보여 준다', () => {
    render(<FileTable items={many} selected={new Set()} onSelectionChange={vi.fn()} onOpen={vi.fn()} onContextAction={vi.fn()} />)
    expect(rowCount()).toBe(200)

    fireEvent.click(button('나머지 250개 더 보기'))
    expect(rowCount()).toBe(400)
    fireEvent.click(button('나머지 50개 더 보기'))
    expect(rowCount()).toBe(450)
    expect(screen.queryByText(/더 보기/)).not.toBeInTheDocument()
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

  it('정렬을 바꾸면 다시 처음 200개부터 그린다', () => {
    render(<FileTable items={many} selected={new Set()} onSelectionChange={vi.fn()} onOpen={vi.fn()} onContextAction={vi.fn()} />)
    fireEvent.click(button('나머지 250개 더 보기'))
    expect(rowCount()).toBe(400)
    fireEvent.click(button('크기'))
    expect(rowCount()).toBe(200)
  })
})

describe('FileTable — 키보드 단축키의 대상 [FB-01]', () => {
  // 정렬 결과: 자료(folder-3), 보고서 2.txt(file-2), 보고서 10.txt(file-1)
  const row = (i: number) => {
    const r = document.querySelectorAll<HTMLTableRowElement>('tbody tr[data-row]')[i]
    if (!r) throw new Error(`행 ${i} 없음`)
    return r
  }
  const setup = (selected: string[]) => {
    const onDeleteKey = vi.fn()
    const onRenameKey = vi.fn()
    render(
      <FileTable items={items} selected={new Set(selected)} onSelectionChange={vi.fn()} onOpen={vi.fn()}
        onContextAction={vi.fn()} onDeleteKey={onDeleteKey} onRenameKey={onRenameKey} />,
    )
    return { onDeleteKey, onRenameKey }
  }

  it('선택하지 않은 행에서 Delete 를 누르면 이전 선택이 아니라 그 행만 지운다', () => {
    const { onDeleteKey } = setup(['file-1'])
    fireEvent.keyDown(row(1), { key: 'Delete' })
    expect(onDeleteKey).toHaveBeenCalledWith([items[1]])
  })

  it('선택한 행에서 Delete 를 누르면 선택한 항목을 모두 지운다', () => {
    const { onDeleteKey } = setup(['file-1', 'file-2'])
    fireEvent.keyDown(row(1), { key: 'Delete' })
    expect(onDeleteKey).toHaveBeenCalledWith([items[1], items[0]])
  })

  it('F2 는 이전 선택과 상관없이 키를 누른 행의 이름을 바꾼다', () => {
    const { onRenameKey } = setup(['file-2'])
    fireEvent.keyDown(row(2), { key: 'F2' })
    expect(onRenameKey).toHaveBeenCalledWith(items[0])
  })
})

describe('FileTable — 행 안의 버튼 [FB-09]', () => {
  it('작업 메뉴 버튼에서 Enter 를 누르면 메뉴만 열고 폴더는 열지 않는다 (Enter 가 두 번 처리되던 문제)', async () => {
    const onOpen = vi.fn()
    const onContextAction = vi.fn()
    const onSelectionChange = vi.fn()
    render(<FileTable items={items} selected={new Set()} onSelectionChange={onSelectionChange} onOpen={onOpen} onContextAction={onContextAction} />)
    const user = userEvent.setup()

    screen.getByRole('button', { name: '자료 작업 메뉴' }).focus()
    await user.keyboard('{Enter}')
    expect(onContextAction).toHaveBeenCalledOnce()
    expect(onOpen).not.toHaveBeenCalled()

    screen.getByLabelText('보고서 2.txt 선택').focus()
    await user.keyboard(' ')
    expect(onSelectionChange).toHaveBeenCalledTimes(1)
  })
})
