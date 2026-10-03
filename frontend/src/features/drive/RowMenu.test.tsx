import { fireEvent, render, screen } from '@testing-library/react'
import type { Item } from '@/api/types'
import { RowMenu } from './RowMenu'

const file: Item = {
  type: 'file', id: 1, name: 'a.txt', size: 10, ownerName: '김하늘', createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z', previewKind: 'TEXT', textEditable: true,
}

describe('RowMenu', () => {
  it('열리면 첫 항목에 초점을 두고, 화면이 다시 그려져도 옮긴 초점을 빼앗지 않는다 [FB-10]', () => {
    const view = render(<RowMenu x={300} y={100} item={file} canEdit onClose={() => {}} onAction={() => {}} />)
    expect(screen.getByRole('menuitem', { name: '미리보기' })).toHaveFocus()
    screen.getByRole('menuitem', { name: '복사' }).focus()

    view.rerender(<RowMenu x={300} y={100} item={file} canEdit onClose={() => {}} onAction={() => {}} />)

    expect(screen.getByRole('menuitem', { name: '복사' })).toHaveFocus()
  })

  it('ESC 를 누르면 최신 onClose 로 닫는다', () => {
    const first = vi.fn()
    const latest = vi.fn()
    const view = render(<RowMenu x={300} y={100} item={file} canEdit onClose={first} onAction={() => {}} />)
    view.rerender(<RowMenu x={300} y={100} item={file} canEdit onClose={latest} onAction={() => {}} />)

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(latest).toHaveBeenCalledOnce()
    expect(first).not.toHaveBeenCalled()
  })
})
